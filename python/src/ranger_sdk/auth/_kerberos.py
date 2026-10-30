"""Kerberos / SPNEGO provider (optional dependency: ``pip install ranger-sdk[kerberos]``)."""

from __future__ import annotations

import base64
import os
import threading
from typing import Any, Dict, Optional, Tuple

from ranger_sdk.auth._base import AuthProvider


class Kerberos(AuthProvider):
    """SPNEGO ``Negotiate`` authentication.

    With ``keytab`` the principal logs in from the keytab into a private
    in-memory credential cache, so neither ``kinit`` nor the system default
    keytab is needed. Without it the current ticket cache is used.
    ``krb5_conf`` is applied explicitly instead of relying on ``/etc/krb5.conf``.

    The provider is meant to be combined with Ranger Admin's session cookie
    (``RANGERADMINSESSIONID``): the transport only sends a Negotiate token when
    it holds no session, which saves one round trip per request.
    """

    servers: Tuple[str, ...] = ("admin", "kms", "pdp")
    supports_session_reuse = True

    def __init__(
        self,
        principal: Optional[str] = None,
        keytab: Optional[str] = None,
        krb5_conf: Optional[str] = None,
        service: str = "HTTP",
        hostname_override: Optional[str] = None,
    ) -> None:
        if keytab and not principal:
            raise ValueError("principal is required when keytab is given")
        self.principal = principal
        self.keytab = keytab
        self.krb5_conf = krb5_conf
        self.service = service
        self.hostname_override = hostname_override
        self._creds: Any = None
        self._lock = threading.Lock()
        if krb5_conf:
            # MIT krb5 reads KRB5_CONFIG lazily on first context creation.
            os.environ["KRB5_CONFIG"] = krb5_conf

    def _gssapi(self) -> Any:
        try:
            import gssapi  # type: ignore
        except ImportError as e:  # pragma: no cover - depends on environment
            raise ImportError("Kerberos auth needs the 'gssapi' package: pip install ranger-sdk[kerberos]") from e
        return gssapi

    def _credentials(self) -> Any:
        gssapi = self._gssapi()
        with self._lock:
            if self._creds is not None:
                try:
                    if self._creds.lifetime > 60:
                        return self._creds
                except gssapi.exceptions.GSSError:
                    pass
            if self.keytab:
                name = gssapi.Name(self.principal, gssapi.NameType.kerberos_principal)
                store = {"client_keytab": self.keytab, "ccache": f"MEMORY:ranger-sdk-{id(self)}"}
                self._creds = gssapi.Credentials(name=name, usage="initiate", store=store)
            elif self.principal:
                name = gssapi.Name(self.principal, gssapi.NameType.kerberos_principal)
                self._creds = gssapi.Credentials(name=name, usage="initiate")
            else:
                self._creds = gssapi.Credentials(usage="initiate")
            return self._creds

    def headers(self, host: str) -> Dict[str, str]:
        gssapi = self._gssapi()
        target = gssapi.Name(f"{self.service}@{self.hostname_override or host}", gssapi.NameType.hostbased_service)
        ctx = gssapi.SecurityContext(
            name=target,
            creds=self._credentials(),
            usage="initiate",
            mech=gssapi.Mechanism.from_sasl_name("SPNEGO"),
        )
        token = ctx.step()
        return {"Authorization": "Negotiate " + base64.b64encode(token).decode()}

    def refresh(self) -> bool:
        with self._lock:
            self._creds = None
        return True

    def describe(self) -> str:
        return f"Kerberos(principal={self.principal!r}, keytab={'***' if self.keytab else None})"
