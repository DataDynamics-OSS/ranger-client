"""Ranger PDP client (``/authz/v1``, Ranger 2.9+)."""

from __future__ import annotations

import json
import threading
import time
import uuid
from collections import OrderedDict
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple, Union

from ranger_sdk._client import _AsyncBase, _SyncBase
from ranger_sdk._flow import Flow, flow
from ranger_sdk._transport import Request, RetryPolicy
from ranger_sdk._transport.core import REQUEST_ID_HEADER
from ranger_sdk.models import (
    AccessContext,
    AccessInfo,
    AuthzRequest,
    AuthzResult,
    MatchScope,
    MultiAuthzRequest,
    MultiAuthzResult,
    ResourceInfo,
    ResourcePermissions,
    ResourcePermissionsRequest,
    UserInfo,
    resource_name,
)
from ranger_sdk.models.pdp import ResourceSpec

BASE = "/authz/v1"
Access = Union[str, Sequence[str]]


class _TtlCache:
    """Small thread-safe LRU with a TTL, keyed by the request body (minus requestId)."""

    def __init__(self, ttl: float, size: int) -> None:
        self.ttl = ttl
        self.size = size
        self._data: "OrderedDict[str, Tuple[float, Any]]" = OrderedDict()
        self._lock = threading.Lock()

    @staticmethod
    def key(path: str, body: Dict[str, Any]) -> str:
        body = {k: v for k, v in body.items() if k != "requestId"}
        if isinstance(body.get("context"), dict):
            body["context"] = {k: v for k, v in body["context"].items() if k != "accessTime"}
        return path + json.dumps(body, sort_keys=True, separators=(",", ":"))

    def get(self, key: str) -> Any:
        with self._lock:
            hit = self._data.get(key)
            if hit is None:
                return None
            if hit[0] < time.monotonic():
                del self._data[key]
                return None
            self._data.move_to_end(key)
            return hit[1]

    def put(self, key: str, value: Any) -> None:
        with self._lock:
            self._data[key] = (time.monotonic() + self.ttl, value)
            self._data.move_to_end(key)
            while len(self._data) > self.size:
                self._data.popitem(last=False)

    def clear(self) -> None:
        with self._lock:
            self._data.clear()


def _perms(access: Access) -> List[str]:
    return [access] if isinstance(access, str) else list(access)


def _resource(resource: ResourceSpec, sub_resources: Optional[Iterable[str]] = None,
              match_scope: Optional[Union[str, MatchScope]] = None,
              attributes: Optional[Dict[str, Any]] = None) -> ResourceInfo:
    return ResourceInfo(
        name=resource_name(resource),
        sub_resources=list(sub_resources) if sub_resources else None,
        name_match_scope=MatchScope(match_scope) if match_scope else None,
        attributes=attributes,
    )


class _PdpMixin:
    _runner: Any
    _defaults: Dict[str, Any]
    _cache: Optional[_TtlCache]

    def _init_pdp(self, service: Optional[str], service_type: Optional[str],
                  cache_ttl: Optional[float], cache_size: int) -> None:
        self._defaults = {"service": service, "service_type": service_type}
        self._cache = _TtlCache(cache_ttl, cache_size) if cache_ttl else None

    def _context(self, service: Optional[str], service_type: Optional[str], client_ip: Optional[str],
                 additional_info: Optional[Dict[str, Any]]) -> AccessContext:
        return AccessContext(
            service_name=service or self._defaults["service"],
            service_type=service_type or self._defaults["service_type"],
            access_time=int(time.time() * 1000),
            client_ip_address=client_ip,
            additional_info=additional_info,
        )

    def _user(self, user: Union[str, UserInfo], groups: Optional[Iterable[str]], roles: Optional[Iterable[str]],
              attributes: Optional[Dict[str, Any]]) -> UserInfo:
        if isinstance(user, UserInfo):
            return user
        return UserInfo(name=user, groups=list(groups) if groups else None, roles=list(roles) if roles else None,
                        attributes=attributes)

    def _post(self, path: str, body: Dict[str, Any], parse: Any) -> Flow[Any]:
        key = _TtlCache.key(path, body) if self._cache else None
        if key is not None:
            hit = self._cache.get(key)  # type: ignore[union-attr]
            if hit is not None:
                return hit
        # authorization calls are side-effect free, so they are retried like GETs
        # the body's requestId doubles as X-Request-Id, so header, body and result carry the same id
        rid = body.get("requestId") if isinstance(body, dict) else None
        headers = {REQUEST_ID_HEADER: rid} if rid else None
        result = parse((yield Request("POST", path, json=body, headers=headers, idempotent=True)))
        if key is not None:
            self._cache.put(key, result)  # type: ignore[union-attr]
        return result

    @flow
    def authorize(
        self,
        request: Optional[Union[AuthzRequest, Dict[str, Any]]] = None,
        *,
        user: Optional[Union[str, UserInfo]] = None,
        resource: Optional[ResourceSpec] = None,
        access: Optional[Access] = None,
        service: Optional[str] = None,
        service_type: Optional[str] = None,
        groups: Optional[Iterable[str]] = None,
        roles: Optional[Iterable[str]] = None,
        user_attributes: Optional[Dict[str, Any]] = None,
        action: Optional[str] = None,
        sub_resources: Optional[Iterable[str]] = None,
        match_scope: Optional[Union[str, MatchScope]] = None,
        client_ip: Optional[str] = None,
        additional_info: Optional[Dict[str, Any]] = None,
        request_id: Optional[str] = None,
    ) -> Flow[AuthzResult]:
        """Single authorization decision.

        Either pass a full ``request`` or the keyword form::

            pdp.authorize(user="alice", resource={"database": "sales", "table": "orders"},
                          access="select", service="dev_hive", service_type="hive")

        When ``user`` differs from the authenticated caller, the caller must be
        a delegation user of the service (otherwise 403).
        """
        if request is None:
            if user is None or resource is None or access is None:
                raise ValueError("authorize() needs request= or user=, resource= and access=")
            request = AuthzRequest(
                request_id=request_id or uuid.uuid4().hex,
                user=self._user(user, groups, roles, user_attributes),
                access=AccessInfo(resource=_resource(resource, sub_resources, match_scope), action=action,
                                  permissions=_perms(access)),
                context=self._context(service, service_type, client_ip, additional_info),
            )
        req = request if isinstance(request, AuthzRequest) else AuthzRequest.model_validate(request)
        return (yield from self._post(f"{BASE}/authorize", req.to_api(), AuthzResult.model_validate))

    @flow
    def authorize_multi(
        self,
        request: Optional[Union[MultiAuthzRequest, Dict[str, Any]]] = None,
        *,
        user: Optional[Union[str, UserInfo]] = None,
        accesses: Optional[Sequence[Union[AccessInfo, Tuple[ResourceSpec, Access]]]] = None,
        service: Optional[str] = None,
        service_type: Optional[str] = None,
        groups: Optional[Iterable[str]] = None,
        roles: Optional[Iterable[str]] = None,
        user_attributes: Optional[Dict[str, Any]] = None,
        client_ip: Optional[str] = None,
        additional_info: Optional[Dict[str, Any]] = None,
        request_id: Optional[str] = None,
    ) -> Flow[MultiAuthzResult]:
        """Several accesses for one user in one round trip.

        ``accesses`` items are :class:`AccessInfo` or ``(resource, access)`` tuples;
        results come back in the same order.
        """
        if request is None:
            if user is None or not accesses:
                raise ValueError("authorize_multi() needs request= or user= and accesses=")
            infos = [a if isinstance(a, AccessInfo) else AccessInfo(resource=_resource(a[0]), permissions=_perms(a[1]))
                     for a in accesses]
            request = MultiAuthzRequest(
                request_id=request_id or uuid.uuid4().hex,
                user=self._user(user, groups, roles, user_attributes),
                accesses=infos,
                context=self._context(service, service_type, client_ip, additional_info),
            )
        req = request if isinstance(request, MultiAuthzRequest) else MultiAuthzRequest.model_validate(request)
        return (yield from self._post(f"{BASE}/authorizeMulti", req.to_api(), MultiAuthzResult.model_validate))

    @flow
    def permissions(
        self,
        request: Optional[Union[ResourcePermissionsRequest, Dict[str, Any]]] = None,
        *,
        resource: Optional[ResourceSpec] = None,
        service: Optional[str] = None,
        service_type: Optional[str] = None,
        request_id: Optional[str] = None,
    ) -> Flow[ResourcePermissions]:
        """Who (users, groups, roles) has which permission on ``resource``."""
        if request is None:
            if resource is None:
                raise ValueError("permissions() needs request= or resource=")
            request = ResourcePermissionsRequest(
                request_id=request_id or uuid.uuid4().hex,
                resource=_resource(resource),
                context=self._context(service, service_type, None, None),
            )
        req = request if isinstance(request, ResourcePermissionsRequest) else \
            ResourcePermissionsRequest.model_validate(request)
        return (yield from self._post(f"{BASE}/permissions", req.to_api(), ResourcePermissions.model_validate))

    @flow
    def ready(self) -> Flow[Dict[str, Any]]:
        """Readiness of the PDP server (``GET /health/ready``); never retried.

        ``status`` is ``READY`` when the authorizer is initialised and accepting requests; a not-ready
        server answers 503, raised as :class:`~ranger_sdk.errors.RangerServerException`.
        """
        return (yield Request("GET", "/health/ready", retry=RetryPolicy(max_attempts=1)))

    @flow
    def is_allowed(self, user: str, resource: ResourceSpec, access: Access, **kw: Any) -> Flow[bool]:
        result = yield from type(self).authorize.flow(self, user=user, resource=resource, access=access, **kw)
        return result.allowed

    def clear_cache(self) -> None:
        if self._cache:
            self._cache.clear()


class RangerPdp(_SyncBase, _PdpMixin):
    """Blocking PDP client.

    ``service``/``service_type`` set defaults for every call. ``cache_ttl``
    (seconds) enables a client-side decision cache; leave it off when policy
    changes must be visible immediately.
    """

    _server = "pdp"

    def __init__(self, urls: Union[str, Sequence[str]], *, service: Optional[str] = None,
                 service_type: Optional[str] = None, cache_ttl: Optional[float] = None, cache_size: int = 10000,
                 **options: Any) -> None:
        super().__init__(urls, **options)
        self._init_pdp(service, service_type, cache_ttl, cache_size)


class AsyncRangerPdp(_AsyncBase, _PdpMixin):
    """asyncio PDP client with the same methods as :class:`RangerPdp` (awaitable)."""

    _server = "pdp"

    def __init__(self, urls: Union[str, Sequence[str]], *, service: Optional[str] = None,
                 service_type: Optional[str] = None, cache_ttl: Optional[float] = None, cache_size: int = 10000,
                 **options: Any) -> None:
        super().__init__(urls, **options)
        self._init_pdp(service, service_type, cache_ttl, cache_size)


__all__ = ["RangerPdp", "AsyncRangerPdp"]
