"""Runs spec/contract/pac.yaml (shared with the Java SDK)."""

import os

import pytest
import respx
import yaml

from conftest import ADMIN, SPEC_DIR
from fake_ranger import FakeRanger
from ranger_sdk import RangerAdmin
from ranger_sdk.pac import PolicyAsCode, parse_spec

with open(os.path.join(SPEC_DIR, "contract", "pac.yaml"), encoding="utf-8") as fh:
    CASES = yaml.safe_load(fh)["cases"]


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_pac_case(case):
    fake = FakeRanger()
    for p in case["server"]:
        fake.add_policy(**p)
    with respx.mock(base_url=ADMIN) as m:
        m.route().mock(side_effect=fake)
        plan = PolicyAsCode(RangerAdmin(ADMIN)).plan(parse_spec(case["spec"]))
    assert plan.summary() == case["expect"]["summary"], plan.render()
    got = []
    for c in plan.pending:
        entry = [c.action, c.name]
        if c.action == "update":
            entry.append([f for f, _, _ in c.diff])
        got.append(entry)
    expected = [e if len(e) > 2 or e[0] != "update" else e for e in case["expect"]["changes"]]
    assert [g[: len(e)] for g, e in zip(got, expected)] == expected and len(got) == len(expected), got
