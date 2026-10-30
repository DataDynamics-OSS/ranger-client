"""Ranger Admin clients."""

from __future__ import annotations

from typing import Any, Optional, Sequence, Union

from ranger_sdk._client import _AsyncBase, _SyncBase
from ranger_sdk.admin._resources import Gds, Groups, Policies, Roles, ServiceDefs, Services, Tags, Users, Zones
from ranger_sdk.capabilities import Capabilities


class _AdminMixin:
    _runner: Any

    def _init_admin(self, server_version: Optional[str]) -> None:
        self.capabilities = Capabilities(self._runner, "admin", server_version)
        args = (self._runner, self.capabilities)
        self.service_defs = ServiceDefs(*args)
        self.services = Services(*args)
        self.policies = Policies(*args)
        self.roles = Roles(*args)
        self.zones = Zones(*args)
        self.users = Users(*args)
        self.groups = Groups(*args)
        self.tags = Tags(*args)
        self.gds = Gds(*args)


class RangerAdmin(_SyncBase, _AdminMixin):
    """Blocking Ranger Admin client. Thread-safe; create one per application.

    >>> admin = RangerAdmin(["https://ranger1:6182", "https://ranger2:6182"],
    ...                     auth=Basic("admin", lambda: vault.read("ranger")))
    >>> admin.policies.apply(policy)
    """

    def __init__(self, urls: Union[str, Sequence[str]], *, server_version: Optional[str] = None,
                 **options: Any) -> None:
        super().__init__(urls, **options)
        self._init_admin(server_version)


class AsyncRangerAdmin(_AsyncBase, _AdminMixin):
    """asyncio Ranger Admin client with the same methods as :class:`RangerAdmin` (awaitable)."""

    def __init__(self, urls: Union[str, Sequence[str]], *, server_version: Optional[str] = None,
                 **options: Any) -> None:
        super().__init__(urls, **options)
        self._init_admin(server_version)


__all__ = ["RangerAdmin", "AsyncRangerAdmin"]
