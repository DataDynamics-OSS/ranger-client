"""Server capability detection driven by ``features.yaml`` (shared with the Java SDK)."""

from __future__ import annotations

import threading
from enum import Enum
from functools import lru_cache
from importlib import resources
from typing import Any, Dict, Optional, Tuple

import yaml

from ranger_sdk._flow import Flow, flow
from ranger_sdk._transport import Request
from ranger_sdk.errors import RangerException, RangerNotFoundException, UnsupportedFeatureException


class Feature(str, Enum):
    SERVICE_DEFS = "SERVICE_DEFS"
    SERVICES = "SERVICES"
    POLICIES = "POLICIES"
    ROLES = "ROLES"
    USERS = "USERS"
    TAGS = "TAGS"
    ZONES = "ZONES"
    ZONES_V2 = "ZONES_V2"
    GDS = "GDS"
    KMS = "KMS"
    PDP = "PDP"


@lru_cache(maxsize=1)
def feature_matrix() -> Dict[str, Any]:
    text = resources.files("ranger_sdk").joinpath("features.yaml").read_text(encoding="utf-8")
    return yaml.safe_load(text)


def parse_version(v: str) -> Tuple[int, ...]:
    out = []
    for part in v.split("-")[0].split("."):
        digits = "".join(ch for ch in part if ch.isdigit())
        out.append(int(digits) if digits else 0)
    while len(out) < 3:
        out.append(0)
    return tuple(out)


class Capabilities:
    """``admin.capabilities.supports(Feature.GDS)``.

    With ``server_version`` configured the answer comes from the feature matrix
    alone; otherwise each feature's probe endpoint is requested once and the
    result cached (404 = absent, anything else = present).
    """

    def __init__(self, runner: Any, server: str, server_version: Optional[str] = None) -> None:
        self._runner = runner
        self.server = server
        self.server_version = server_version
        self._cache: Dict[str, bool] = {}
        self._lock = threading.Lock()

    def _spec(self, feature: Feature) -> Dict[str, Any]:
        return feature_matrix()["features"][Feature(feature).value]

    def _probe(self, feature: Feature) -> Flow[bool]:
        key = Feature(feature).value
        with self._lock:
            if key in self._cache:
                return self._cache[key]
        spec = self._spec(feature)
        if spec["server"] != self.server:
            supported = False
        elif self.server_version:
            supported = parse_version(self.server_version) >= parse_version(spec["min_version"])
        elif not spec.get("probe"):
            supported = True
        else:
            path, _, query = spec["probe"].partition("?")
            params = dict(p.split("=", 1) for p in query.split("&") if p) if query else None
            try:
                yield Request("GET", path, params=params)
                supported = True
            except RangerNotFoundException:
                supported = False
            except RangerException as exc:
                # 401/403/400 mean the endpoint exists; transport failures propagate
                if exc.status is None:
                    raise
                supported = True
        with self._lock:
            self._cache[key] = supported
        return supported

    supports = flow(_probe)

    def require(self, feature: Feature) -> Flow[None]:
        if not (yield from self._probe(feature)):
            spec = self._spec(feature)
            raise UnsupportedFeatureException(
                Feature(feature).value,
                f"needs Ranger {spec['server']} >= {spec['min_version']}"
                + (f", server is {self.server_version}" if self.server_version else ""),
            )
