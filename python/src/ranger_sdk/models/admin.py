"""Ranger Admin models (public v2 API and xusers)."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Union

from pydantic import AliasChoices, Field, field_validator

from ranger_sdk.models._base import BaseObject, RangerModel

# --------------------------------------------------------------------------- service defs / services


class ServiceDef(BaseObject):
    name: Optional[str] = None
    display_name: Optional[str] = None
    impl_class: Optional[str] = None
    label: Optional[str] = None
    description: Optional[str] = None
    options: Optional[Dict[str, str]] = None
    configs: Optional[List[Dict[str, Any]]] = None
    resources: Optional[List[Dict[str, Any]]] = None
    access_types: Optional[List[Dict[str, Any]]] = None
    policy_conditions: Optional[List[Dict[str, Any]]] = None
    context_enrichers: Optional[List[Dict[str, Any]]] = None
    enums: Optional[List[Dict[str, Any]]] = None
    data_mask_def: Optional[Dict[str, Any]] = None
    row_filter_def: Optional[Dict[str, Any]] = None

    def resource_names(self) -> List[str]:
        return [r["name"] for r in self.resources or [] if "name" in r]

    def access_type_names(self) -> List[str]:
        return [a["name"] for a in self.access_types or [] if "name" in a]


class Service(BaseObject):
    type: Optional[str] = None
    name: Optional[str] = None
    display_name: Optional[str] = None
    description: Optional[str] = None
    tag_service: Optional[str] = None
    configs: Optional[Dict[str, str]] = None
    policy_version: Optional[int] = None
    policy_update_time: Optional[Any] = None
    tag_version: Optional[int] = None
    tag_update_time: Optional[Any] = None


# --------------------------------------------------------------------------- policies


class PolicyResource(RangerModel):
    values: List[str] = Field(default_factory=list)
    is_excludes: Optional[bool] = None
    is_recursive: Optional[bool] = None

    @classmethod
    def _coerce(cls, v: Any) -> Any:
        if isinstance(v, str):
            return {"values": [v]}
        if isinstance(v, (list, tuple, set)):
            return {"values": list(v)}
        return v


def _coerce_resources(v: Any) -> Any:
    if isinstance(v, dict):
        return {k: PolicyResource._coerce(x) for k, x in v.items()}
    return v


class PolicyItemAccess(RangerModel):
    type: str
    is_allowed: bool = True


class PolicyItemCondition(RangerModel):
    type: str
    values: List[str] = Field(default_factory=list)


class PolicyItem(RangerModel):
    accesses: List[PolicyItemAccess] = Field(default_factory=list)
    users: Optional[List[str]] = None
    groups: Optional[List[str]] = None
    roles: Optional[List[str]] = None
    conditions: Optional[List[PolicyItemCondition]] = None
    delegate_admin: Optional[bool] = None

    @field_validator("accesses", mode="before")
    @classmethod
    def _accesses(cls, v: Any) -> Any:
        if isinstance(v, (list, tuple)):
            return [{"type": a} if isinstance(a, str) else a for a in v]
        return v

    @field_validator("conditions", mode="before")
    @classmethod
    def _conditions(cls, v: Any) -> Any:
        if isinstance(v, dict):  # {"ip-range": ["10.0.0.0/8"]}
            return [{"type": k, "values": x if isinstance(x, list) else [x]} for k, x in v.items()]
        return v


class DataMaskInfo(RangerModel):
    data_mask_type: Optional[str] = None
    condition_expr: Optional[str] = None
    value_expr: Optional[str] = None


class DataMaskPolicyItem(PolicyItem):
    data_mask_info: Optional[DataMaskInfo] = None


class RowFilterInfo(RangerModel):
    filter_expr: Optional[str] = None


class RowFilterPolicyItem(PolicyItem):
    row_filter_info: Optional[RowFilterInfo] = None


POLICY_TYPE_ACCESS = 0
POLICY_TYPE_DATAMASK = 1
POLICY_TYPE_ROWFILTER = 2


def _alias(*names: str) -> Any:
    return Field(default=None, validation_alias=AliasChoices(*names), serialization_alias=names[0])


class Policy(BaseObject):
    """A Ranger policy.

    Convenience inputs accepted by the constructor::

        Policy(service="cm_hive", name="sales_read",
               resources={"database": ["sales"], "table": "*"},
               allow=[PolicyItem(groups=["analyst"], accesses=["select"])])
    """

    service: Optional[str] = None
    name: Optional[str] = None
    policy_type: Optional[int] = None
    policy_priority: Optional[int] = None
    description: Optional[str] = None
    resource_signature: Optional[str] = None
    is_audit_enabled: Optional[bool] = None
    resources: Optional[Dict[str, PolicyResource]] = None
    additional_resources: Optional[List[Dict[str, PolicyResource]]] = None
    conditions: Optional[List[PolicyItemCondition]] = None
    policy_items: Optional[List[PolicyItem]] = _alias("policyItems", "policy_items", "allow")
    deny_policy_items: Optional[List[PolicyItem]] = _alias("denyPolicyItems", "deny_policy_items", "deny")
    allow_exceptions: Optional[List[PolicyItem]] = None
    deny_exceptions: Optional[List[PolicyItem]] = None
    data_mask_policy_items: Optional[List[DataMaskPolicyItem]] = None
    row_filter_policy_items: Optional[List[RowFilterPolicyItem]] = None
    service_type: Optional[str] = None
    options: Optional[Dict[str, Any]] = None
    validity_schedules: Optional[List[Dict[str, Any]]] = None
    policy_labels: Optional[List[str]] = None
    zone_name: Optional[str] = None
    is_deny_all_else: Optional[bool] = None

    @field_validator("resources", mode="before")
    @classmethod
    def _resources(cls, v: Any) -> Any:
        return _coerce_resources(v)

    @field_validator("additional_resources", mode="before")
    @classmethod
    def _additional(cls, v: Any) -> Any:
        if isinstance(v, list):
            return [_coerce_resources(x) for x in v]
        return v

    def resource_values(self) -> Dict[str, List[str]]:
        return {k: list(r.values) for k, r in (self.resources or {}).items()}


# --------------------------------------------------------------------------- roles


class RoleMember(RangerModel):
    name: str
    is_admin: bool = False


def _members(v: Any) -> Any:
    if isinstance(v, (list, tuple)):
        return [{"name": m} if isinstance(m, str) else m for m in v]
    return v


class Role(BaseObject):
    name: Optional[str] = None
    description: Optional[str] = None
    options: Optional[Dict[str, Any]] = None
    users: Optional[List[RoleMember]] = None
    groups: Optional[List[RoleMember]] = None
    roles: Optional[List[RoleMember]] = None
    created_by_user: Optional[str] = None

    @field_validator("users", "groups", "roles", mode="before")
    @classmethod
    def _m(cls, v: Any) -> Any:
        return _members(v)


class GrantRevokeRoleRequest(RangerModel):
    grantor: Optional[str] = None
    grantor_groups: Optional[List[str]] = None
    target_roles: List[str] = Field(default_factory=list)
    users: Optional[List[str]] = None
    groups: Optional[List[str]] = None
    roles: Optional[List[str]] = None
    grant_option: Optional[bool] = None
    client_ip_address: Optional[str] = Field(default=None, alias="clientIPAddress")
    client_type: Optional[str] = None
    request_data: Optional[str] = None
    session_id: Optional[str] = None
    cluster_name: Optional[str] = None


# --------------------------------------------------------------------------- security zones


class ZoneServiceResource(RangerModel):
    id: Optional[int] = None
    resource: Dict[str, List[str]] = Field(default_factory=dict)


class ZoneService(RangerModel):
    resources: List[ZoneServiceResource] = Field(default_factory=list)

    @field_validator("resources", mode="before")
    @classmethod
    def _res(cls, v: Any) -> Any:
        if isinstance(v, list):
            return [x if (not isinstance(x, dict) or "resource" in x) else {"resource": x} for x in v]
        return v


class Principal(RangerModel):
    type: str  # USER | GROUP | ROLE
    name: str


class SecurityZone(BaseObject):
    """Security zone in the v2 shape (``/api/zones-v2``)."""

    name: Optional[str] = None
    description: Optional[str] = None
    services: Optional[Dict[str, ZoneService]] = None
    tag_services: Optional[List[str]] = None
    admins: Optional[List[Principal]] = None
    auditors: Optional[List[Principal]] = None


class SecurityZoneChangeRequest(RangerModel):
    name: Optional[str] = None
    description: Optional[str] = None
    resources_to_update: Optional[Dict[str, ZoneService]] = None
    resources_to_remove: Optional[Dict[str, ZoneService]] = None
    tag_services_to_add: Optional[List[str]] = None
    tag_services_to_remove: Optional[List[str]] = None
    admins_to_add: Optional[List[Principal]] = None
    admins_to_remove: Optional[List[Principal]] = None
    auditors_to_add: Optional[List[Principal]] = None
    auditors_to_remove: Optional[List[Principal]] = None


# --------------------------------------------------------------------------- users / groups (xusers)


class User(RangerModel):
    id: Optional[int] = None
    name: Optional[str] = None
    first_name: Optional[str] = None
    last_name: Optional[str] = None
    email_address: Optional[str] = None
    password: Optional[str] = Field(default=None, repr=False)
    description: Optional[str] = None
    group_id_list: Optional[List[int]] = None
    group_name_list: Optional[List[str]] = None
    status: Optional[int] = None
    is_visible: Optional[int] = None
    user_source: Optional[int] = None
    user_role_list: Optional[List[str]] = None
    other_attributes: Optional[str] = None
    sync_source: Optional[str] = None


class Group(RangerModel):
    id: Optional[int] = None
    name: Optional[str] = None
    description: Optional[str] = None
    group_type: Optional[int] = None
    group_source: Optional[int] = None
    is_visible: Optional[int] = None
    other_attributes: Optional[str] = None
    sync_source: Optional[str] = None


class GroupUser(RangerModel):
    id: Optional[int] = None
    name: Optional[str] = None  # group name
    parent_group_id: Optional[int] = None
    user_id: Optional[int] = None


# --------------------------------------------------------------------------- tags


class TagAttributeDef(RangerModel):
    name: str
    type: str = "string"


class TagDef(BaseObject):
    name: Optional[str] = None
    source: Optional[str] = None
    attribute_defs: Optional[List[TagAttributeDef]] = None


class Tag(BaseObject):
    type: Optional[str] = None
    owner: Optional[int] = None
    attributes: Optional[Dict[str, str]] = None
    options: Optional[Dict[str, Any]] = None
    validity_periods: Optional[List[Dict[str, Any]]] = None


class ServiceResource(BaseObject):
    service_name: Optional[str] = None
    resource_elements: Optional[Dict[str, PolicyResource]] = None
    resource_signature: Optional[str] = None
    owner_user: Optional[str] = None
    additional_info: Optional[Dict[str, str]] = None

    @field_validator("resource_elements", mode="before")
    @classmethod
    def _res(cls, v: Any) -> Any:
        return _coerce_resources(v)


class TagResourceMap(BaseObject):
    tag_id: Optional[int] = None
    resource_id: Optional[int] = None


# --------------------------------------------------------------------------- GDS


class GdsAcl(RangerModel):
    users: Optional[Dict[str, str]] = None  # name -> GdsPermission (ADMIN, POLICY_ADMIN, LIST, VIEW, AUDIT)
    groups: Optional[Dict[str, str]] = None
    roles: Optional[Dict[str, str]] = None


class Dataset(BaseObject):
    name: Optional[str] = None
    description: Optional[str] = None
    acl: Optional[GdsAcl] = None
    terms_of_use: Optional[str] = None
    options: Optional[Dict[str, str]] = None
    additional_info: Optional[Dict[str, str]] = None


class Project(BaseObject):
    name: Optional[str] = None
    description: Optional[str] = None
    acl: Optional[GdsAcl] = None
    terms_of_use: Optional[str] = None
    options: Optional[Dict[str, str]] = None
    additional_info: Optional[Dict[str, str]] = None


class DataShare(BaseObject):
    name: Optional[str] = None
    description: Optional[str] = None
    acl: Optional[GdsAcl] = None
    service: Optional[str] = None
    zone: Optional[str] = None
    condition_expr: Optional[str] = None
    default_access_types: Optional[List[str]] = None
    default_tag_masks: Optional[List[Dict[str, Any]]] = None
    terms_of_use: Optional[str] = None
    options: Optional[Dict[str, str]] = None
    additional_info: Optional[Dict[str, str]] = None


PolicyLike = Union[Policy, Dict[str, Any]]
