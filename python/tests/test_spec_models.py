"""The Pydantic models must expose exactly the wire fields listed in spec/models.yaml (Java is generated from it)."""

import os

import pytest
import yaml
from pydantic.alias_generators import to_camel

import ranger_sdk.models as models
from conftest import SPEC_DIR

with open(os.path.join(SPEC_DIR, "models.yaml"), encoding="utf-8") as fh:
    SPEC = yaml.safe_load(fh)


def _wire_names(cls):
    # excluded fields are SDK-side helpers (e.g. decision_raw), never sent on the wire
    return {f.alias or to_camel(name) for name, f in cls.model_fields.items() if not f.exclude}


@pytest.mark.parametrize("name", sorted(SPEC["models"]))
def test_fields_match_spec(name):
    m = SPEC["models"][name]
    cls = getattr(models, m["python"], None)
    assert cls is not None, f"ranger_sdk.models.{m['python']} missing (spec model {name})"
    expected = set(m.get("fields") or {})
    if m.get("base"):
        expected |= set(SPEC["base_fields"])
    assert _wire_names(cls) == expected


def test_wire_alias_round_trip():
    req = models.GrantRevokeRoleRequest.from_api({"clientIPAddress": "10.0.0.1", "targetRoles": ["r"]})
    assert req.client_ip_address == "10.0.0.1"
    assert req.to_api()["clientIPAddress"] == "10.0.0.1"


def test_java_models_are_generated_from_the_spec():
    import subprocess
    import sys

    root = os.path.dirname(SPEC_DIR)
    gen = os.path.join(root, "tools", "gen_java_models.py")
    if not os.path.exists(os.path.join(root, "java")):
        pytest.skip("java sources not present")
    out = subprocess.run([sys.executable, gen, "--check"], capture_output=True, text=True)
    assert out.returncode == 0, out.stdout + out.stderr
