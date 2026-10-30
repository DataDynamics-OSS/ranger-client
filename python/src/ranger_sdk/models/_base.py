from __future__ import annotations

from enum import Enum
from typing import Any, Dict, Optional, Type, TypeVar

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

M = TypeVar("M", bound="RangerModel")


class RangerModel(BaseModel):
    """Base for all wire models.

    * fields are snake_case in Python and camelCase on the wire
    * unknown server fields are kept (``extra='allow'``) and sent back unchanged
      on update, so a newer server never loses data through an older SDK
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="allow",
        validate_assignment=False,
        use_enum_values=False,
        protected_namespaces=(),
    )

    def to_api(self) -> Dict[str, Any]:
        """JSON-ready dict in the server's (camelCase) shape."""
        return self.model_dump(by_alias=True, exclude_none=True, mode="json")

    @classmethod
    def from_api(cls: Type[M], data: Any) -> M:
        return cls.model_validate(data)

    @property
    def extra_fields(self) -> Dict[str, Any]:
        """Fields the server sent that this SDK version does not model."""
        return dict(self.__pydantic_extra__ or {})


class BaseObject(RangerModel):
    """Fields common to every Ranger admin object (``RangerBaseModelObject``)."""

    id: Optional[int] = None
    guid: Optional[str] = None
    is_enabled: Optional[bool] = None
    created_by: Optional[str] = None
    updated_by: Optional[str] = None
    create_time: Optional[Any] = None
    update_time: Optional[Any] = None
    version: Optional[int] = None


class OpenEnum(str, Enum):
    """String enum that maps unknown server values to ``UNKNOWN``."""

    @classmethod
    def _missing_(cls, value: object) -> Optional["OpenEnum"]:
        if isinstance(value, str):
            for m in cls:
                if m.value.upper() == value.upper():
                    return m
        return cls.__members__.get("UNKNOWN")  # type: ignore[return-value]
