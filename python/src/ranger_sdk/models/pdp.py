"""PDP (``/authz/v1``) models, mirroring ``org.apache.ranger.authz.model``."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence, Tuple, Union

from pydantic import Field, model_validator

from ranger_sdk.models._base import OpenEnum, RangerModel


class Decision(OpenEnum):
    ALLOW = "ALLOW"
    DENY = "DENY"
    NOT_DETERMINED = "NOT_DETERMINED"
    PARTIAL = "PARTIAL"
    UNKNOWN = "UNKNOWN"


class MatchScope(OpenEnum):
    SELF = "SELF"
    SELF_OR_ANY_CHILD = "SELF_OR_ANY_CHILD"
    SELF_OR_ANY_DESCENDANT = "SELF_OR_ANY_DESCENDANT"
    UNKNOWN = "UNKNOWN"


ResourceSpec = Union[str, Dict[str, str], Sequence[Tuple[str, str]]]


def resource_name(resource: ResourceSpec) -> str:
    """Build a Ranger Resource Name (RRN): ``<leaf-type>:<v1>/<v2>/...``.

    ``{"database": "sales", "table": "orders"}`` -> ``table:sales/orders``.
    Pass resources from the top of the hierarchy down; ``/`` and ``\\`` in
    values are escaped. A string is taken as an RRN already.
    """
    if isinstance(resource, str):
        return resource
    items = list(resource.items()) if isinstance(resource, dict) else [tuple(x) for x in resource]
    if not items:
        raise ValueError("resource must not be empty")
    values = [str(v).replace("\\", "\\\\").replace("/", "\\/") for _, v in items]
    return f"{items[-1][0]}:" + "/".join(values)


class UserInfo(RangerModel):
    name: str
    attributes: Optional[Dict[str, Any]] = None
    groups: Optional[List[str]] = None
    roles: Optional[List[str]] = None


class ResourceInfo(RangerModel):
    name: str
    sub_resources: Optional[List[str]] = None
    name_match_scope: Optional[MatchScope] = None
    attributes: Optional[Dict[str, Any]] = None


class AccessInfo(RangerModel):
    resource: ResourceInfo
    action: Optional[str] = None
    permissions: List[str] = Field(default_factory=list)


class AccessContext(RangerModel):
    service_type: Optional[str] = None
    service_name: Optional[str] = None
    access_time: Optional[int] = None
    client_ip_address: Optional[str] = None
    forwarded_ip_addresses: Optional[List[str]] = None
    additional_info: Optional[Dict[str, Any]] = None


class AuthzRequest(RangerModel):
    request_id: Optional[str] = None
    user: UserInfo
    access: AccessInfo
    context: AccessContext


class MultiAuthzRequest(RangerModel):
    request_id: Optional[str] = None
    user: UserInfo
    accesses: List[AccessInfo]
    context: AccessContext


class ResourcePermissionsRequest(RangerModel):
    request_id: Optional[str] = None
    resource: ResourceInfo
    context: AccessContext


class PolicyInfo(RangerModel):
    id: Optional[int] = None
    version: Optional[int] = None


class _HasDecision(RangerModel):
    decision: Optional[Decision] = None
    decision_raw: Optional[str] = Field(default=None, exclude=True)

    @model_validator(mode="before")
    @classmethod
    def _keep_raw(cls, data: Any) -> Any:
        if isinstance(data, dict) and isinstance(data.get("decision"), str):
            data = dict(data)
            data.setdefault("decisionRaw", data["decision"])
        return data

    @property
    def allowed(self) -> bool:
        return self.decision == Decision.ALLOW


class AccessResult(_HasDecision):
    policy: Optional[PolicyInfo] = None


class DataMaskResult(RangerModel):
    mask_type: Optional[str] = None
    masked_value: Optional[str] = None
    policy: Optional[PolicyInfo] = None


class RowFilterResult(RangerModel):
    filter_expr: Optional[str] = None
    policy: Optional[PolicyInfo] = None


class ResultInfo(RangerModel):
    access: Optional[AccessResult] = None
    data_mask: Optional[DataMaskResult] = None
    row_filter: Optional[RowFilterResult] = None
    additional_info: Optional[Dict[str, Any]] = None


class PermissionResult(ResultInfo):
    permission: Optional[str] = None
    sub_resources: Optional[Dict[str, ResultInfo]] = None


class AuthzResult(_HasDecision):
    request_id: Optional[str] = None
    permissions: Optional[Dict[str, PermissionResult]] = None


class MultiAuthzResult(_HasDecision):
    request_id: Optional[str] = None
    accesses: Optional[List[AuthzResult]] = None


class ResourcePermissions(RangerModel):
    resource: Optional[ResourceInfo] = None
    users: Optional[Dict[str, Dict[str, PermissionResult]]] = None
    groups: Optional[Dict[str, Dict[str, PermissionResult]]] = None
    roles: Optional[Dict[str, Dict[str, PermissionResult]]] = None
