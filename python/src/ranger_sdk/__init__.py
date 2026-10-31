"""Ranger SDK: typed Apache Ranger clients for Admin, KMS and PDP.

>>> from ranger_sdk import RangerAdmin
>>> from ranger_sdk.auth import Basic
>>> admin = RangerAdmin("https://ranger:6182", auth=Basic("admin", "secret"))
"""

from ranger_sdk._transport import RetryPolicy
from ranger_sdk.admin import AsyncRangerAdmin, RangerAdmin
from ranger_sdk.capabilities import Feature
from ranger_sdk.errors import (
    RangerAccessDeniedException,
    RangerAuthenticationException,
    RangerConflictException,
    RangerException,
    RangerNotFoundException,
    RangerServerException,
    RangerTransportException,
    RangerValidationException,
    UnsupportedFeatureException,
)
from ranger_sdk.kms import AsyncRangerKms, RangerKms
from ranger_sdk.pdp import AsyncRangerPdp, RangerPdp

__version__ = "0.2.0"

__all__ = [
    "RangerAdmin",
    "AsyncRangerAdmin",
    "RangerKms",
    "AsyncRangerKms",
    "RangerPdp",
    "AsyncRangerPdp",
    "RetryPolicy",
    "Feature",
    "RangerException",
    "RangerTransportException",
    "RangerAuthenticationException",
    "RangerAccessDeniedException",
    "RangerNotFoundException",
    "RangerConflictException",
    "RangerValidationException",
    "RangerServerException",
    "UnsupportedFeatureException",
]
