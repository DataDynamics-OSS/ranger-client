"""Ranger KMS (Hadoop KMS REST) models."""

from __future__ import annotations

from typing import Any, Dict, Optional

from pydantic import Field

from ranger_sdk.models._base import RangerModel


class KeyMetadata(RangerModel):
    name: Optional[str] = None
    cipher: Optional[str] = None
    length: Optional[int] = None
    description: Optional[str] = None
    attributes: Optional[Dict[str, Any]] = None
    created: Optional[int] = None
    versions: Optional[int] = None


class KeyVersion(RangerModel):
    name: Optional[str] = None
    version_name: Optional[str] = None
    material: Optional[str] = Field(default=None, repr=False)


class EncryptedKeyVersion(RangerModel):
    version_name: Optional[str] = None
    iv: Optional[str] = None
    encrypted_key_version: Optional[KeyVersion] = None
