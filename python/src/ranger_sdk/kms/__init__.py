"""Ranger KMS client (Hadoop KMS REST API, ``/kms/v1``)."""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional, Sequence, Union

from ranger_sdk._client import _AsyncBase, _SyncBase
from ranger_sdk._flow import Flow, call, flow
from ranger_sdk._transport import Request, seg
from ranger_sdk.errors import RangerNotFoundException
from ranger_sdk.models import EncryptedKeyVersion, KeyMetadata, KeyVersion

V1 = "/kms/v1"


def _one(model: Any) -> Any:
    return lambda d: model.model_validate(d) if d else None


def _many(model: Any) -> Any:
    return lambda d: [model.model_validate(x) for x in d or []]


def _existing(req: Request, what: str, parse: Any) -> Any:
    """KMS reports an unknown key or key version as ``200 {}`` (``[]`` for its versions) rather than 404."""
    def check(d: Any) -> Any:
        if not d:
            raise RangerNotFoundException(f"Not found: {what}", status=200, method=req.method, path=req.path,
                                          body=json.dumps(d))
        return parse(d)
    return check


class _KmsMixin:
    _runner: Any

    @flow
    def create_key(self, name: str, *, cipher: str = "AES/CTR/NoPadding", length: int = 128,
                   description: Optional[str] = None, attributes: Optional[Dict[str, str]] = None,
                   material: Optional[str] = None) -> Flow[KeyVersion]:
        body = {"name": name, "cipher": cipher, "length": length, "description": description,
                "attributes": attributes, "material": material}
        body = {k: v for k, v in body.items() if v is not None}
        return (yield from call(Request("POST", f"{V1}/keys", json=body), _one(KeyVersion)))

    @flow
    def rollover_key(self, name: str, material: Optional[str] = None) -> Flow[KeyVersion]:
        body = {"material": material} if material else {}
        return (yield from call(Request("POST", f"{V1}/key/{seg(name)}", json=body), _one(KeyVersion)))

    @flow
    def delete_key(self, name: str) -> Flow[None]:
        yield Request("DELETE", f"{V1}/key/{seg(name)}")

    @flow
    def invalidate_cache(self, name: str) -> Flow[None]:
        yield Request("POST", f"{V1}/key/{seg(name)}/_invalidatecache", idempotent=True)

    @flow
    def metadata(self, name: str) -> Flow[KeyMetadata]:
        req = Request("GET", f"{V1}/key/{seg(name)}/_metadata")
        return (yield from call(req, _existing(req, f"key {name}", _one(KeyMetadata))))

    @flow
    def metadata_bulk(self, names: Sequence[str]) -> Flow[List[KeyMetadata]]:
        return (yield from call(Request("GET", f"{V1}/keys/metadata", params={"key": list(names)}), _many(KeyMetadata)))

    @flow
    def current_version(self, name: str) -> Flow[KeyVersion]:
        req = Request("GET", f"{V1}/key/{seg(name)}/_currentversion")
        return (yield from call(req, _existing(req, f"key {name}", _one(KeyVersion))))

    @flow
    def versions(self, name: str) -> Flow[List[KeyVersion]]:
        req = Request("GET", f"{V1}/key/{seg(name)}/_versions")
        return (yield from call(req, _existing(req, f"key {name}", _many(KeyVersion))))

    @flow
    def key_version(self, version_name: str) -> Flow[KeyVersion]:
        req = Request("GET", f"{V1}/keyversion/{seg(version_name)}")
        return (yield from call(req, _existing(req, f"key version {version_name}", _one(KeyVersion))))

    @flow
    def names(self) -> Flow[List[str]]:
        return (yield from call(Request("GET", f"{V1}/keys/names"), lambda d: list(d or [])))

    @flow
    def generate_eek(self, name: str, num_keys: int = 1) -> Flow[List[EncryptedKeyVersion]]:
        params = {"eek_op": "generate", "num_keys": num_keys}
        return (yield from call(Request("GET", f"{V1}/key/{seg(name)}/_eek", params=params), _many(EncryptedKeyVersion)))

    def _eek_body(self, name: str, eek: Union[EncryptedKeyVersion, Dict[str, Any]]) -> Dict[str, Any]:
        e = eek if isinstance(eek, EncryptedKeyVersion) else EncryptedKeyVersion.model_validate(eek)
        material = e.encrypted_key_version.material if e.encrypted_key_version else None
        return {"name": name, "iv": e.iv, "material": material}

    @flow
    def decrypt_eek(self, name: str, eek: Union[EncryptedKeyVersion, Dict[str, Any]]) -> Flow[KeyVersion]:
        e = eek if isinstance(eek, EncryptedKeyVersion) else EncryptedKeyVersion.model_validate(eek)
        req = Request("POST", f"{V1}/keyversion/{seg(e.version_name)}/_eek", params={"eek_op": "decrypt"},
                      json=self._eek_body(name, e), idempotent=True)
        return (yield from call(req, _one(KeyVersion)))

    @flow
    def reencrypt_eek(self, name: str, eek: Union[EncryptedKeyVersion, Dict[str, Any]]) -> Flow[EncryptedKeyVersion]:
        e = eek if isinstance(eek, EncryptedKeyVersion) else EncryptedKeyVersion.model_validate(eek)
        req = Request("POST", f"{V1}/keyversion/{seg(e.version_name)}/_eek", params={"eek_op": "reencrypt"},
                      json=self._eek_body(name, e), idempotent=True)
        return (yield from call(req, _one(EncryptedKeyVersion)))

    @flow
    def status(self) -> Flow[Any]:
        return (yield Request("GET", "/kms/api/status"))


class RangerKms(_SyncBase, _KmsMixin):
    """Blocking Ranger KMS client."""

    _server = "kms"


class AsyncRangerKms(_AsyncBase, _KmsMixin):
    """asyncio Ranger KMS client."""

    _server = "kms"


__all__ = ["RangerKms", "AsyncRangerKms"]
