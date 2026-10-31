"""Admin sub-clients. Every method is a flow, so it works on both sync and async clients."""

from __future__ import annotations

import json
from typing import Any, Dict, Iterable, List, Optional, Sequence, Type, TypeVar, Union

from ranger_sdk._flow import Flow, call, flow
from ranger_sdk._transport import Request, seg
from ranger_sdk.capabilities import Capabilities, Feature
from ranger_sdk.errors import RangerConflictException, RangerNotFoundException, RangerValidationException
from ranger_sdk.models import (
    DataShare,
    Dataset,
    GrantRevokeRoleRequest,
    Group,
    GroupUser,
    Policy,
    PolicyItem,
    Project,
    RangerModel,
    Role,
    SecurityZone,
    SecurityZoneChangeRequest,
    Service,
    ServiceDef,
    ServiceResource,
    Tag,
    TagDef,
    TagResourceMap,
    User,
)

API = "/service/public/v2/api"
XUSERS = "/service/xusers"
TAGS = "/service/tags"
GDS = "/service/gds"
PLUGINS = "/service/plugins"

M = TypeVar("M", bound=RangerModel)
DEFAULT_PAGE = 200


def _body(obj: Any) -> Any:
    if isinstance(obj, RangerModel):
        return obj.to_api()
    return obj


def _one(model: Type[M]) -> Any:
    return lambda data: model.model_validate(data) if data is not None else None


def _many(model: Type[M], key: Optional[str] = None) -> Any:
    def parse(data: Any) -> List[M]:
        if isinstance(data, dict):  # PList / VList wrappers
            data = data.get(key) if key else (data.get("list") or data.get("vXList") or [])
        return [model.model_validate(x) for x in data or []]

    return parse


def _coerce(model: Type[M], obj: Union[M, Dict[str, Any]]) -> M:
    return obj if isinstance(obj, model) else model.model_validate(obj)


def _not_found(exc: RangerValidationException) -> RangerNotFoundException:
    """Re-raise a 400 that Ranger uses for "no such object" as :class:`RangerNotFoundException`."""
    return RangerNotFoundException(
        f"Not found: {exc.server_message or f'{exc.method} {exc.path}'}", status=exc.status, method=exc.method, path=exc.path,
        server=exc.server, request_id=exc.request_id, body=exc.body, server_message=exc.server_message,
    )


def _paging(start_index: Optional[int], page_size: Optional[int]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    if start_index is not None:
        out["startIndex"] = start_index
    if page_size is not None:
        out["pageSize"] = page_size
    return out


class _Sub:
    def __init__(self, runner: Any, caps: Capabilities) -> None:
        self._runner = runner
        self._caps = caps

    def _paginate(self, search: Any, page_size: int, **filters: Any) -> Any:
        return self._runner.paginate(
            lambda start: search.flow(self, start_index=start, page_size=page_size, **filters), page_size)


# --------------------------------------------------------------------------- service defs


class ServiceDefs(_Sub):
    @flow
    def get(self, id: int) -> Flow[ServiceDef]:
        return (yield from call(Request("GET", f"{API}/servicedef/{int(id)}"), _one(ServiceDef)))

    @flow
    def get_by_name(self, name: str) -> Flow[ServiceDef]:
        return (yield from call(Request("GET", f"{API}/servicedef/name/{seg(name)}"), _one(ServiceDef)))

    @flow
    def search(self, start_index: Optional[int] = None, page_size: Optional[int] = None,
               **filters: Any) -> Flow[List[ServiceDef]]:
        params = {**filters, **_paging(start_index, page_size)}
        return (yield from call(Request("GET", f"{API}/servicedef", params=params), _many(ServiceDef)))

    def iter(self, page_size: int = DEFAULT_PAGE, **filters: Any) -> Any:
        return self._paginate(ServiceDefs.search, page_size, **filters)

    @flow
    def create(self, service_def: Union[ServiceDef, Dict[str, Any]]) -> Flow[ServiceDef]:
        body = _body(_coerce(ServiceDef, service_def))
        return (yield from call(Request("POST", f"{API}/servicedef", json=body), _one(ServiceDef)))

    @flow
    def update(self, service_def: Union[ServiceDef, Dict[str, Any]]) -> Flow[ServiceDef]:
        sd = _coerce(ServiceDef, service_def)
        path = f"{API}/servicedef/{sd.id}" if sd.id is not None else f"{API}/servicedef/name/{seg(sd.name)}"
        return (yield from call(Request("PUT", path, json=sd.to_api()), _one(ServiceDef)))

    @flow
    def delete(self, id: int) -> Flow[None]:
        yield Request("DELETE", f"{API}/servicedef/{int(id)}")

    @flow
    def delete_by_name(self, name: str) -> Flow[None]:
        yield Request("DELETE", f"{API}/servicedef/name/{seg(name)}")


# --------------------------------------------------------------------------- services


class Services(_Sub):
    @flow
    def get(self, id: int) -> Flow[Service]:
        return (yield from call(Request("GET", f"{API}/service/{int(id)}"), _one(Service)))

    @flow
    def get_by_name(self, name: str) -> Flow[Service]:
        return (yield from call(Request("GET", f"{API}/service/name/{seg(name)}"), _one(Service)))

    @flow
    def find(self, name: str) -> Flow[Optional[Service]]:
        try:
            return (yield from Services.get_by_name.flow(self, name))
        except RangerNotFoundException:
            return None

    @flow
    def search(self, service_type: Optional[str] = None, name: Optional[str] = None,
               start_index: Optional[int] = None, page_size: Optional[int] = None,
               **filters: Any) -> Flow[List[Service]]:
        params = {"serviceType": service_type, "serviceName": name, **filters, **_paging(start_index, page_size)}
        return (yield from call(Request("GET", f"{API}/service", params=params), _many(Service)))

    def iter(self, page_size: int = DEFAULT_PAGE, **filters: Any) -> Any:
        return self._paginate(Services.search, page_size, **filters)

    @flow
    def create(self, service: Union[Service, Dict[str, Any]]) -> Flow[Service]:
        body = _body(_coerce(Service, service))
        return (yield from call(Request("POST", f"{API}/service", json=body), _one(Service)))

    @flow
    def update(self, service: Union[Service, Dict[str, Any]]) -> Flow[Service]:
        svc = _coerce(Service, service)
        path = f"{API}/service/{svc.id}" if svc.id is not None else f"{API}/service/name/{seg(svc.name)}"
        return (yield from call(Request("PUT", path, json=svc.to_api()), _one(Service)))

    @flow
    def apply(self, service: Union[Service, Dict[str, Any]]) -> Flow[Service]:
        """Create the service, or update the existing one with the same name."""
        svc = _coerce(Service, service)
        current = yield from Services.find.flow(self, svc.name)
        if current is None:
            return (yield from Services.create.flow(self, svc))
        merged = svc.model_copy(update={"id": current.id, "guid": current.guid, "version": current.version})
        return (yield from Services.update.flow(self, merged))

    @flow
    def delete(self, id: int) -> Flow[None]:
        yield Request("DELETE", f"{API}/service/{int(id)}")

    @flow
    def delete_by_name(self, name: str) -> Flow[None]:
        yield Request("DELETE", f"{API}/service/name/{seg(name)}")

    @flow
    def test_connection(self, service: Union[Service, Dict[str, Any]]) -> Flow[Dict[str, Any]]:
        """Run the plugin's connection check (``validateConfig``); ``statusCode`` 0 means success."""
        body = _body(_coerce(Service, service))
        return (yield from call(Request("POST", f"{PLUGINS}/services/validateConfig", json=body, idempotent=True)))


# --------------------------------------------------------------------------- policies

_ITEM_FIELDS = {
    "allow": "policy_items",
    "deny": "deny_policy_items",
    "allow_exceptions": "allow_exceptions",
    "deny_exceptions": "deny_exceptions",
}


class Policies(_Sub):
    @flow
    def get(self, id: int) -> Flow[Policy]:
        return (yield from call(Request("GET", f"{API}/policy/{int(id)}"), _one(Policy)))

    @flow
    def get_by_name(self, service: str, name: str, zone: Optional[str] = None) -> Flow[Policy]:
        req = Request("GET", f"{API}/service/{seg(service)}/policy/{seg(name)}", params={"zoneName": zone})
        return (yield from call(req, _one(Policy)))

    @flow
    def find(self, service: str, name: str, zone: Optional[str] = None) -> Flow[Optional[Policy]]:
        """Like :meth:`get_by_name` but returns ``None`` when the policy does not exist."""
        try:
            return (yield from Policies.get_by_name.flow(self, service, name, zone))
        except RangerNotFoundException:
            return None

    @flow
    def search(
        self,
        service: Optional[str] = None,
        name: Optional[str] = None,
        *,
        zone: Optional[str] = None,
        policy_type: Optional[int] = None,
        resource: Optional[Dict[str, str]] = None,
        user: Optional[str] = None,
        group: Optional[str] = None,
        role: Optional[str] = None,
        label: Optional[str] = None,
        enabled: Optional[bool] = None,
        start_index: Optional[int] = None,
        page_size: Optional[int] = None,
        **filters: Any,
    ) -> Flow[List[Policy]]:
        params: Dict[str, Any] = {
            "serviceName": service, "policyName": name, "zoneName": zone, "policyType": policy_type,
            "user": user, "group": group, "role": role, "policyLabelsPartial": label, "isEnabled": enabled,
        }
        for k, v in (resource or {}).items():
            params[f"resource:{k}"] = v
        params.update(filters)
        params.update(_paging(start_index, page_size))
        return (yield from call(Request("GET", f"{API}/policy", params=params), _many(Policy)))

    def iter(self, service: Optional[str] = None, page_size: int = DEFAULT_PAGE, **filters: Any) -> Any:
        """Iterate over every matching policy, fetching pages lazily."""
        return self._paginate(Policies.search, page_size, service=service, **filters)

    @flow
    def list_in_service(self, service: str) -> Flow[List[Policy]]:
        return (yield from call(Request("GET", f"{API}/service/{seg(service)}/policy"), _many(Policy)))

    @flow
    def for_resource(self, service_type: str, service: str, resource: Dict[str, str]) -> Flow[List[Policy]]:
        """Policies whose resources match ``resource`` (e.g. ``{"database": "sales", "table": "orders"}``)."""
        params: Dict[str, Any] = {"serviceName": service}
        params.update({f"resource:{k}": v for k, v in resource.items()})
        req = Request("GET", f"{API}/policies/{seg(service_type)}/for-resource/", params=params)
        return (yield from call(req, _many(Policy)))

    @flow
    def for_user(self, user: str, service: Optional[str] = None) -> Flow[List[Policy]]:
        """Policies that name ``user`` directly (groups/roles are not expanded; use PDP for decisions)."""
        return (yield from Policies.search.flow(self, service, user=user))

    @flow
    def create(self, policy: Union[Policy, Dict[str, Any]]) -> Flow[Policy]:
        body = _body(_coerce(Policy, policy))
        return (yield from call(Request("POST", f"{API}/policy", json=body), _one(Policy)))

    @flow
    def update(self, policy: Union[Policy, Dict[str, Any]], check_version: bool = True) -> Flow[Policy]:
        """Update by id.

        When ``check_version`` is set and the policy carries a ``version``, the
        current server version is compared first and a mismatch raises
        :class:`RangerConflictException` instead of silently overwriting a
        concurrent change.
        """
        p = _coerce(Policy, policy)
        if p.id is None:
            raise ValueError("policy.id is required for update(); use update_by_name() or apply()")
        if check_version and p.version is not None:
            current = yield from Policies.get.flow(self, p.id)
            if current.version is not None and current.version != p.version:
                raise RangerConflictException(
                    f"Policy {p.id} changed on the server (version {current.version}, expected {p.version})",
                    status=409, method="PUT", path=f"{API}/policy/{p.id}")
        return (yield from call(Request("PUT", f"{API}/policy/{p.id}", json=p.to_api()), _one(Policy)))

    @flow
    def update_by_name(self, service: str, name: str, policy: Union[Policy, Dict[str, Any]],
                       zone: Optional[str] = None) -> Flow[Policy]:
        body = _body(_coerce(Policy, policy))
        req = Request("PUT", f"{API}/service/{seg(service)}/policy/{seg(name)}", params={"zoneName": zone}, json=body)
        return (yield from call(req, _one(Policy)))

    @flow
    def apply(self, policy: Union[Policy, Dict[str, Any]]) -> Flow[Policy]:
        """Name-based upsert: create when absent, otherwise replace the existing policy.

        Safe to re-run (unlike a bare POST); this is what the policy-as-code
        engine uses.
        """
        p = _coerce(Policy, policy)
        if not p.service or not p.name:
            raise ValueError("apply() needs policy.service and policy.name")
        for attempt in range(2):
            current = yield from Policies.find.flow(self, p.service, p.name, p.zone_name)
            if current is None:
                try:
                    return (yield from Policies.create.flow(self, p))
                except RangerConflictException:
                    if attempt:  # someone else created it meanwhile: update instead
                        raise
                    continue
            merged = p.model_copy(update={"id": current.id, "guid": current.guid, "version": current.version})
            return (yield from Policies.update.flow(self, merged, check_version=False))
        raise AssertionError("unreachable")

    @flow
    def server_apply(self, policy: Union[Policy, Dict[str, Any]]) -> Flow[Policy]:
        """Ranger's own ``/policy/apply`` (merges into a policy with the same resources)."""
        body = _body(_coerce(Policy, policy))
        return (yield from call(Request("POST", f"{API}/policy/apply", json=body), _one(Policy)))

    @flow
    def delete(self, id: int) -> Flow[None]:
        yield Request("DELETE", f"{API}/policy/{int(id)}")

    @flow
    def delete_by_name(self, service: str, name: str, zone: Optional[str] = None) -> Flow[None]:
        params = {"servicename": service, "policyname": name, "zoneName": zone}
        yield Request("DELETE", f"{API}/policy", params=params)

    @flow
    def add_items(self, service: str, name: str, items: Sequence[Union[PolicyItem, Dict[str, Any]]],
                  kind: str = "allow", zone: Optional[str] = None) -> Flow[Policy]:
        """Append policy items (``kind``: allow, deny, allow_exceptions, deny_exceptions)."""
        field = _ITEM_FIELDS[kind]
        p = yield from Policies.get_by_name.flow(self, service, name, zone)
        current = list(getattr(p, field) or [])
        current.extend(_coerce(PolicyItem, i) for i in items)
        updated = p.model_copy(update={field: current})
        return (yield from Policies.update.flow(self, updated, check_version=True))

    @flow
    def remove_principals(self, service: str, name: str, *, users: Iterable[str] = (), groups: Iterable[str] = (),
                          roles: Iterable[str] = (), kind: str = "allow", zone: Optional[str] = None) -> Flow[Policy]:
        """Remove principals from the policy's items; items left without principals are dropped."""
        field = _ITEM_FIELDS[kind]
        drop = {"users": set(users), "groups": set(groups), "roles": set(roles)}
        p = yield from Policies.get_by_name.flow(self, service, name, zone)
        kept = []
        for item in getattr(p, field) or []:
            upd = {k: [x for x in (getattr(item, k) or []) if x not in drop[k]] for k in drop}
            if any(upd.values()):
                kept.append(item.model_copy(update=upd))
        updated = p.model_copy(update={field: kept})
        return (yield from Policies.update.flow(self, updated, check_version=True))

    @flow
    def export_json(self, services: Optional[Sequence[str]] = None, zone: Optional[str] = None) -> Flow[Dict[str, Any]]:
        """Ranger's bulk export (``exportJson``): ``{"metaDataInfo": ..., "policies": [...]}``."""
        params = {"serviceName": ",".join(services) if services else None, "zoneName": zone,
                  "checkPoliciesExists": False}
        # Ranger answers this endpoint with Content-Type text/json and 406 to "Accept: application/json" alone.
        data = yield Request("GET", f"{PLUGINS}/policies/exportJson", params=params,
                             headers={"Accept": "application/json, text/json"})
        if isinstance(data, str):
            data = json.loads(data) if data else {}
        return data or {"policies": []}

    @flow
    def import_json(self, data: Dict[str, Any], *, override: bool = False,
                    services_map: Optional[Dict[str, str]] = None,
                    zone_map: Optional[Dict[str, str]] = None) -> Flow[None]:
        """Ranger's bulk import (``importPoliciesFromFile``). Not idempotent unless ``override``."""
        files: Dict[str, Any] = {"file": ("policies.json", json.dumps(data).encode(), "application/json")}
        if services_map:
            files["servicesMapJson"] = ("servicesMap.json", json.dumps(services_map).encode(), "application/json")
        if zone_map:
            files["zoneMapJson"] = ("zoneMap.json", json.dumps(zone_map).encode(), "application/json")
        yield Request("POST", f"{PLUGINS}/policies/importPoliciesFromFile",
                      params={"isOverride": override}, files=files, idempotent=override)


# --------------------------------------------------------------------------- roles


class Roles(_Sub):
    @flow
    def get(self, id: int) -> Flow[Role]:
        return (yield from call(Request("GET", f"{API}/roles/{int(id)}"), _one(Role)))

    @flow
    def get_by_name(self, name: str) -> Flow[Role]:
        try:
            return (yield from call(Request("GET", f"{API}/roles/name/{seg(name)}"), _one(Role)))
        except RangerValidationException as exc:
            # Ranger 2.x answers an unknown role name with 400: "Role with name: x does not exist", or (2.9)
            # "User doesn't have permissions to get details for x". The latter is also a genuine permission
            # error, so it means "not found" only when the role is absent from the role names.
            msg = exc.server_message or ""
            if "does not exist" in msg:
                raise _not_found(exc) from exc
            if "have permissions to get details for" in msg and name not in (yield from Roles.names.flow(self)):
                raise _not_found(exc) from exc
            raise

    @flow
    def find(self, name: str) -> Flow[Optional[Role]]:
        try:
            return (yield from Roles.get_by_name.flow(self, name))
        except RangerNotFoundException:
            return None

    @flow
    def search(self, name: Optional[str] = None, start_index: Optional[int] = None,
               page_size: Optional[int] = None, **filters: Any) -> Flow[List[Role]]:
        params = {"roleName": name, **filters, **_paging(start_index, page_size)}
        return (yield from call(Request("GET", f"{API}/roles", params=params), _many(Role)))

    def iter(self, page_size: int = DEFAULT_PAGE, **filters: Any) -> Any:
        return self._paginate(Roles.search, page_size, **filters)

    @flow
    def names(self) -> Flow[List[str]]:
        return (yield from call(Request("GET", f"{API}/roles/names"), lambda d: list(d or [])))

    @flow
    def for_user(self, user: str) -> Flow[List[str]]:
        return (yield from call(Request("GET", f"{API}/roles/user/{seg(user)}"), lambda d: list(d or [])))

    @flow
    def create(self, role: Union[Role, Dict[str, Any]], service: Optional[str] = None) -> Flow[Role]:
        body = _body(_coerce(Role, role))
        req = Request("POST", f"{API}/roles", params={"serviceName": service}, json=body)
        return (yield from call(req, _one(Role)))

    @flow
    def update(self, role: Union[Role, Dict[str, Any]]) -> Flow[Role]:
        r = _coerce(Role, role)
        if r.id is None:
            raise ValueError("role.id is required for update(); use apply()")
        return (yield from call(Request("PUT", f"{API}/roles/{r.id}", json=r.to_api()), _one(Role)))

    @flow
    def apply(self, role: Union[Role, Dict[str, Any]]) -> Flow[Role]:
        r = _coerce(Role, role)
        current = yield from Roles.find.flow(self, r.name)
        if current is None:
            return (yield from Roles.create.flow(self, r))
        return (yield from Roles.update.flow(self, r.model_copy(update={"id": current.id, "guid": current.guid})))

    @flow
    def delete(self, id: int) -> Flow[None]:
        yield Request("DELETE", f"{API}/roles/{int(id)}")

    @flow
    def delete_by_name(self, name: str, exec_user: Optional[str] = None, service: Optional[str] = None) -> Flow[None]:
        try:
            yield Request("DELETE", f"{API}/roles/name/{seg(name)}", params={"execUser": exec_user, "serviceName": service})
        except RangerValidationException as exc:
            # Ranger 2.9: 400 "Validation failure: error code[4007], reason[No RangerRole found for name[x]]"
            if "No RangerRole found" in (exc.server_message or ""):
                raise _not_found(exc) from exc
            raise

    @flow
    def grant(self, service: str, request: Union[GrantRevokeRoleRequest, Dict[str, Any]]) -> Flow[Any]:
        body = _body(_coerce(GrantRevokeRoleRequest, request))
        return (yield Request("PUT", f"{API}/roles/grant/{seg(service)}", json=body))

    @flow
    def revoke(self, service: str, request: Union[GrantRevokeRoleRequest, Dict[str, Any]]) -> Flow[Any]:
        body = _body(_coerce(GrantRevokeRoleRequest, request))
        return (yield Request("PUT", f"{API}/roles/revoke/{seg(service)}", json=body))


# --------------------------------------------------------------------------- security zones (v2)


class Zones(_Sub):
    @flow
    def get(self, id: int) -> Flow[SecurityZone]:
        yield from self._caps.require(Feature.ZONES_V2)
        return (yield from call(Request("GET", f"{API}/zones-v2/{int(id)}"), _one(SecurityZone)))

    @flow
    def get_by_name(self, name: str) -> Flow[SecurityZone]:
        yield from self._caps.require(Feature.ZONES_V2)
        try:
            return (yield from call(Request("GET", f"{API}/zones-v2/name/{seg(name)}"), _one(SecurityZone)))
        except RangerValidationException as exc:
            # Ranger 2.x answers an unknown zone name with 400 "security-zone with name: x does not exist".
            if "does not exist" not in (exc.server_message or ""):
                raise
            raise _not_found(exc) from exc

    @flow
    def find(self, name: str) -> Flow[Optional[SecurityZone]]:
        """Like :meth:`get_by_name` but returns ``None`` when the zone does not exist."""
        try:
            return (yield from Zones.get_by_name.flow(self, name))
        except RangerNotFoundException:
            return None

    @flow
    def search(self, name: Optional[str] = None, start_index: Optional[int] = None,
               page_size: Optional[int] = None, **filters: Any) -> Flow[List[SecurityZone]]:
        yield from self._caps.require(Feature.ZONES_V2)
        params = {"zoneName": name, **filters, **_paging(start_index, page_size)}
        return (yield from call(Request("GET", f"{API}/zones-v2", params=params), _many(SecurityZone)))

    def iter(self, page_size: int = DEFAULT_PAGE, **filters: Any) -> Any:
        return self._paginate(Zones.search, page_size, **filters)

    @flow
    def headers(self) -> Flow[List[Dict[str, Any]]]:
        return (yield from call(Request("GET", f"{API}/zone-headers"), lambda d: list(d or [])))

    @flow
    def create(self, zone: Union[SecurityZone, Dict[str, Any]]) -> Flow[SecurityZone]:
        yield from self._caps.require(Feature.ZONES_V2)
        body = _body(_coerce(SecurityZone, zone))
        return (yield from call(Request("POST", f"{API}/zones-v2", json=body), _one(SecurityZone)))

    @flow
    def update(self, zone: Union[SecurityZone, Dict[str, Any]]) -> Flow[SecurityZone]:
        yield from self._caps.require(Feature.ZONES_V2)
        z = _coerce(SecurityZone, zone)
        if z.id is None:
            raise ValueError("zone.id is required for update()")
        return (yield from call(Request("PUT", f"{API}/zones-v2/{z.id}", json=z.to_api()), _one(SecurityZone)))

    @flow
    def update_partial(self, id: int, change: Union[SecurityZoneChangeRequest, Dict[str, Any]]) -> Flow[bool]:
        """Add/remove resources, tag services, admins or auditors without resending the zone."""
        yield from self._caps.require(Feature.ZONES_V2)
        body = _body(_coerce(SecurityZoneChangeRequest, change))
        result = yield Request("PUT", f"{API}/zones-v2/{int(id)}/partial", json=body)
        return bool(result)

    @flow
    def resources(self, id: int, service: str) -> Flow[List[Dict[str, Any]]]:
        yield from self._caps.require(Feature.ZONES_V2)
        data = yield Request("GET", f"{API}/zones-v2/{int(id)}/resources/{seg(service)}")
        return list((data or {}).get("list") or []) if isinstance(data, dict) else list(data or [])

    @flow
    def delete(self, id: int) -> Flow[None]:
        yield Request("DELETE", f"{API}/zones/{int(id)}")

    @flow
    def delete_by_name(self, name: str) -> Flow[None]:
        yield Request("DELETE", f"{API}/zones/name/{seg(name)}")


# --------------------------------------------------------------------------- users / groups


class Users(_Sub):
    @flow
    def get(self, id: int) -> Flow[User]:
        return (yield from call(Request("GET", f"{XUSERS}/secure/users/{int(id)}"), _one(User)))

    @flow
    def get_by_name(self, name: str) -> Flow[User]:
        return (yield from call(Request("GET", f"{XUSERS}/users/userName/{seg(name)}"), _one(User)))

    @flow
    def find(self, name: str) -> Flow[Optional[User]]:
        """Like :meth:`get_by_name` but returns ``None`` when the user does not exist."""
        try:
            return (yield from Users.get_by_name.flow(self, name))
        except RangerNotFoundException:
            return None

    @flow
    def search(self, name: Optional[str] = None, start_index: Optional[int] = None,
               page_size: Optional[int] = None, **filters: Any) -> Flow[List[User]]:
        params = {"name": name, **filters, **_paging(start_index, page_size)}
        return (yield from call(Request("GET", f"{XUSERS}/users", params=params), _many(User, "vXUsers")))

    def iter(self, page_size: int = DEFAULT_PAGE, **filters: Any) -> Any:
        return self._paginate(Users.search, page_size, **filters)

    @flow
    def groups(self, user_id: int) -> Flow[List[Group]]:
        return (yield from call(Request("GET", f"{XUSERS}/{int(user_id)}/groups"), _many(Group, "vXGroups")))

    @flow
    def create(self, user: Union[User, Dict[str, Any]]) -> Flow[User]:
        body = _body(_coerce(User, user))
        return (yield from call(Request("POST", f"{XUSERS}/secure/users", json=body), _one(User)))

    @flow
    def update(self, user: Union[User, Dict[str, Any]]) -> Flow[User]:
        u = _coerce(User, user)
        if u.id is None:
            raise ValueError("user.id is required for update()")
        return (yield from call(Request("PUT", f"{XUSERS}/secure/users/{u.id}", json=u.to_api()), _one(User)))

    @flow
    def delete(self, id: int, force: bool = False) -> Flow[None]:
        yield Request("DELETE", f"{XUSERS}/users/{int(id)}", params={"forceDelete": force})

    @flow
    def delete_by_name(self, name: str, force: bool = False) -> Flow[None]:
        yield Request("DELETE", f"{XUSERS}/users/userName/{seg(name)}", params={"forceDelete": force})


class Groups(_Sub):
    @flow
    def get(self, id: int) -> Flow[Group]:
        return (yield from call(Request("GET", f"{XUSERS}/secure/groups/{int(id)}"), _one(Group)))

    @flow
    def get_by_name(self, name: str) -> Flow[Group]:
        return (yield from call(Request("GET", f"{XUSERS}/groups/groupName/{seg(name)}"), _one(Group)))

    @flow
    def find(self, name: str) -> Flow[Optional[Group]]:
        """Like :meth:`get_by_name` but returns ``None`` when the group does not exist."""
        try:
            return (yield from Groups.get_by_name.flow(self, name))
        except RangerNotFoundException:
            return None

    @flow
    def search(self, name: Optional[str] = None, start_index: Optional[int] = None,
               page_size: Optional[int] = None, **filters: Any) -> Flow[List[Group]]:
        params = {"name": name, **filters, **_paging(start_index, page_size)}
        return (yield from call(Request("GET", f"{XUSERS}/groups", params=params), _many(Group, "vXGroups")))

    def iter(self, page_size: int = DEFAULT_PAGE, **filters: Any) -> Any:
        return self._paginate(Groups.search, page_size, **filters)

    @flow
    def users(self, group_id: int) -> Flow[List[User]]:
        return (yield from call(Request("GET", f"{XUSERS}/{int(group_id)}/users"), _many(User, "vXUsers")))

    @flow
    def create(self, group: Union[Group, Dict[str, Any]]) -> Flow[Group]:
        body = _body(_coerce(Group, group))
        return (yield from call(Request("POST", f"{XUSERS}/secure/groups", json=body), _one(Group)))

    @flow
    def update(self, group: Union[Group, Dict[str, Any]]) -> Flow[Group]:
        g = _coerce(Group, group)
        if g.id is None:
            raise ValueError("group.id is required for update()")
        return (yield from call(Request("PUT", f"{XUSERS}/secure/groups/{g.id}", json=g.to_api()), _one(Group)))

    @flow
    def delete(self, id: int, force: bool = False) -> Flow[None]:
        yield Request("DELETE", f"{XUSERS}/groups/{int(id)}", params={"forceDelete": force})

    @flow
    def delete_by_name(self, name: str, force: bool = False) -> Flow[None]:
        yield Request("DELETE", f"{XUSERS}/groups/groupName/{seg(name)}", params={"forceDelete": force})

    @flow
    def add_user(self, group: str, user: str) -> Flow[GroupUser]:
        """Add ``user`` to ``group`` (both by name)."""
        g = yield from Groups.get_by_name.flow(self, group)
        u = yield from Users.get_by_name.flow(self, user)
        body = GroupUser(name=g.name, parent_group_id=g.id, user_id=u.id).to_api()
        return (yield from call(Request("POST", f"{XUSERS}/groupusers", json=body), _one(GroupUser)))

    @flow
    def remove_user(self, group: str, user: str) -> Flow[None]:
        yield Request("DELETE", f"{XUSERS}/group/{seg(group)}/user/{seg(user)}")


# --------------------------------------------------------------------------- tags


def _by_id(req: Request, parse: Any = None) -> Flow[Any]:
    """Ranger's tag REST answers an unknown id with a bare ``400 {"statusCode":400}``; real failures (e.g. a tag
    still mapped to a resource) carry a message. So a 400 without a message on a by-id call means "not found"."""
    try:
        return (yield from call(req, parse))
    except RangerValidationException as exc:
        if not exc.server_message:
            raise _not_found(exc) from exc
        raise


class Tags(_Sub):
    @flow
    def types(self) -> Flow[List[str]]:
        return (yield from call(Request("GET", f"{TAGS}/types/"), lambda d: list(d or [])))

    # tag definitions
    @flow
    def list_defs(self) -> Flow[List[TagDef]]:
        return (yield from call(Request("GET", f"{TAGS}/tagdefs/"), _many(TagDef)))

    @flow
    def get_def(self, id: int) -> Flow[TagDef]:
        return (yield from _by_id(Request("GET", f"{TAGS}/tagdef/{int(id)}"), _one(TagDef)))

    @flow
    def get_def_by_name(self, name: str) -> Flow[TagDef]:
        return (yield from call(Request("GET", f"{TAGS}/tagdef/name/{seg(name)}"), _one(TagDef)))

    @flow
    def create_def(self, tag_def: Union[TagDef, Dict[str, Any]], update_if_exists: bool = False) -> Flow[TagDef]:
        body = _body(_coerce(TagDef, tag_def))
        req = Request("POST", f"{TAGS}/tagdefs/", params={"updateIfExists": update_if_exists}, json=body)
        return (yield from call(req, _one(TagDef)))

    @flow
    def update_def(self, tag_def: Union[TagDef, Dict[str, Any]]) -> Flow[TagDef]:
        d = _coerce(TagDef, tag_def)
        return (yield from call(Request("PUT", f"{TAGS}/tagdef/{d.id}", json=d.to_api()), _one(TagDef)))

    @flow
    def delete_def(self, id: int) -> Flow[None]:
        yield from _by_id(Request("DELETE", f"{TAGS}/tagdef/{int(id)}"))

    # tags
    @flow
    def list(self) -> Flow[List[Tag]]:
        return (yield from call(Request("GET", f"{TAGS}/tags/"), _many(Tag)))

    @flow
    def by_type(self, type: str) -> Flow[List[Tag]]:
        return (yield from call(Request("GET", f"{TAGS}/tags/type/{seg(type)}"), _many(Tag)))

    @flow
    def get(self, id: int) -> Flow[Tag]:
        return (yield from _by_id(Request("GET", f"{TAGS}/tag/{int(id)}"), _one(Tag)))

    @flow
    def create(self, tag: Union[Tag, Dict[str, Any]], update_if_exists: bool = False) -> Flow[Tag]:
        body = _body(_coerce(Tag, tag))
        req = Request("POST", f"{TAGS}/tags/", params={"updateIfExists": update_if_exists}, json=body)
        return (yield from call(req, _one(Tag)))

    @flow
    def update(self, tag: Union[Tag, Dict[str, Any]]) -> Flow[Tag]:
        t = _coerce(Tag, tag)
        return (yield from call(Request("PUT", f"{TAGS}/tag/{t.id}", json=t.to_api()), _one(Tag)))

    @flow
    def delete(self, id: int) -> Flow[None]:
        yield from _by_id(Request("DELETE", f"{TAGS}/tag/{int(id)}"))

    # service resources
    @flow
    def list_resources(self, service: Optional[str] = None) -> Flow[List[ServiceResource]]:
        path = f"{TAGS}/resources/service/{seg(service)}" if service else f"{TAGS}/resources/"
        return (yield from call(Request("GET", path), _many(ServiceResource)))

    @flow
    def get_resource(self, id: int) -> Flow[ServiceResource]:
        return (yield from _by_id(Request("GET", f"{TAGS}/resource/{int(id)}"), _one(ServiceResource)))

    @flow
    def create_resource(self, resource: Union[ServiceResource, Dict[str, Any]],
                        update_if_exists: bool = False) -> Flow[ServiceResource]:
        body = _body(_coerce(ServiceResource, resource))
        req = Request("POST", f"{TAGS}/resources/", params={"updateIfExists": update_if_exists}, json=body)
        return (yield from call(req, _one(ServiceResource)))

    @flow
    def update_resource(self, resource: Union[ServiceResource, Dict[str, Any]]) -> Flow[ServiceResource]:
        r = _coerce(ServiceResource, resource)
        return (yield from call(Request("PUT", f"{TAGS}/resource/{r.id}", json=r.to_api()), _one(ServiceResource)))

    @flow
    def delete_resource(self, id: int) -> Flow[None]:
        yield from _by_id(Request("DELETE", f"{TAGS}/resource/{int(id)}"))

    # tag <-> resource mapping
    @flow
    def map(self, tag_guid: str, resource_guid: str) -> Flow[TagResourceMap]:
        req = Request("POST", f"{TAGS}/tagresourcemaps/", params={"tag-guid": tag_guid, "resource-guid": resource_guid})
        return (yield from call(req, _one(TagResourceMap)))

    @flow
    def unmap(self, tag_guid: str, resource_guid: str) -> Flow[None]:
        yield Request("DELETE", f"{TAGS}/tagresourcemaps/", params={"tag-guid": tag_guid, "resource-guid": resource_guid})


# --------------------------------------------------------------------------- GDS


class _GdsKind(_Sub):
    kind = ""
    model: Type[RangerModel] = RangerModel

    @flow
    def get(self, id: int) -> Flow[Any]:
        yield from self._caps.require(Feature.GDS)
        return (yield from call(Request("GET", f"{GDS}/{self.kind}/{int(id)}"), _one(self.model)))

    @flow
    def search(self, name: Optional[str] = None, start_index: Optional[int] = None,
               page_size: Optional[int] = None, **filters: Any) -> Flow[List[Any]]:
        yield from self._caps.require(Feature.GDS)
        params = {f"{self.kind}Name": name, **filters, **_paging(start_index, page_size)}
        return (yield from call(Request("GET", f"{GDS}/{self.kind}", params=params), _many(self.model)))

    @flow
    def create(self, obj: Any) -> Flow[Any]:
        yield from self._caps.require(Feature.GDS)
        body = _body(_coerce(self.model, obj))
        return (yield from call(Request("POST", f"{GDS}/{self.kind}", json=body), _one(self.model)))

    @flow
    def update(self, obj: Any) -> Flow[Any]:
        yield from self._caps.require(Feature.GDS)
        o = _coerce(self.model, obj)
        return (yield from call(Request("PUT", f"{GDS}/{self.kind}/{o.id}", json=o.to_api()), _one(self.model)))

    @flow
    def delete(self, id: int) -> Flow[None]:
        yield from self._caps.require(Feature.GDS)
        yield Request("DELETE", f"{GDS}/{self.kind}/{int(id)}")


class _Datasets(_GdsKind):
    kind, model = "dataset", Dataset


class _Projects(_GdsKind):
    kind, model = "project", Project


class _DataShares(_GdsKind):
    kind, model = "datashare", DataShare


class Gds:
    """Governed Data Sharing. Requires a server with GDS (Ranger 3.x line)."""

    def __init__(self, runner: Any, caps: Capabilities) -> None:
        self.datasets = _Datasets(runner, caps)
        self.projects = _Projects(runner, caps)
        self.datashares = _DataShares(runner, caps)
