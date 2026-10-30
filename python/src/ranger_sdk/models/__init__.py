from ranger_sdk.models._base import BaseObject, OpenEnum, RangerModel
from ranger_sdk.models.admin import (
    POLICY_TYPE_ACCESS,
    POLICY_TYPE_DATAMASK,
    POLICY_TYPE_ROWFILTER,
    DataMaskInfo,
    DataMaskPolicyItem,
    DataShare,
    Dataset,
    GdsAcl,
    GrantRevokeRoleRequest,
    Group,
    GroupUser,
    Policy,
    PolicyItem,
    PolicyItemAccess,
    PolicyItemCondition,
    PolicyResource,
    Principal,
    Project,
    Role,
    RoleMember,
    RowFilterInfo,
    RowFilterPolicyItem,
    SecurityZone,
    SecurityZoneChangeRequest,
    Service,
    ServiceDef,
    ServiceResource,
    Tag,
    TagAttributeDef,
    TagDef,
    TagResourceMap,
    User,
    ZoneService,
    ZoneServiceResource,
)
from ranger_sdk.models.kms import EncryptedKeyVersion, KeyMetadata, KeyVersion
from ranger_sdk.models.pdp import (
    AccessContext,
    AccessInfo,
    AccessResult,
    AuthzRequest,
    AuthzResult,
    DataMaskResult,
    Decision,
    MatchScope,
    MultiAuthzRequest,
    MultiAuthzResult,
    PermissionResult,
    PolicyInfo,
    ResourceInfo,
    ResourcePermissions,
    ResourcePermissionsRequest,
    ResultInfo,
    RowFilterResult,
    UserInfo,
    resource_name,
)

_OFFICIAL = {
    "RangerPolicy": Policy, "RangerService": Service, "RangerServiceDef": ServiceDef, "RangerRole": Role,
    "RangerSecurityZone": SecurityZone, "RangerSecurityZoneV2": SecurityZone, "RangerUser": User,
    "RangerGroup": Group, "RangerGroupUser": GroupUser, "RangerTagDef": TagDef, "RangerTag": Tag,
    "RangerServiceResource": ServiceResource, "RangerAuthzRequest": AuthzRequest,
    "RangerAuthzResult": AuthzResult, "RangerKey": KeyMetadata,
}


def from_official(obj, model=None):
    """Convert an object of the official ``apache-ranger`` package into an SDK model.

    The official models are ``dict`` subclasses keyed by camelCase names, so the
    conversion is lossless; unknown keys are kept as extra fields.
    """
    if model is None:
        model = _OFFICIAL.get(type(obj).__name__)
        if model is None:
            raise TypeError(f"no SDK model for {type(obj).__name__}; pass model= explicitly")
    if isinstance(obj, list):
        return [from_official(o, model) for o in obj]
    return model.model_validate(dict(obj))


__all__ = [n for n in dir() if not n.startswith("_")]
