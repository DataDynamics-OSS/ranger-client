import pytest
import respx

from conftest import ADMIN
from fake_ranger import FakeRanger
from ranger_sdk import RangerAdmin, RangerConflictException
from ranger_sdk.pac import PolicyAsCode, dump_spec, load_specs, parse_spec

SPEC = """
service: cm_hive
policies:
  - name: sales_read
    description: Analysts read sales
    resources: {database: sales, table: "*", column: "*"}
    allow:
      - groups: [analyst]
        accesses: [select]
  - name: hr_read
    resources: {database: hr, table: "*", column: "*"}
    allow:
      - users: [carol]
        accesses: [select]
roles:
  - name: analysts
    users: [alice]
"""


@pytest.fixture
def fake():
    f = FakeRanger()
    with respx.mock(base_url=ADMIN) as m:
        m.route().mock(side_effect=f)
        yield f


def _pac():
    return PolicyAsCode(RangerAdmin(ADMIN))


def test_plan_apply_converges(fake, tmp_path):
    (tmp_path / "hive.yaml").write_text(SPEC)
    specs = load_specs(str(tmp_path))
    pac = _pac()
    plan = pac.plan(specs)
    assert plan.summary() == {"create": 3, "update": 0, "delete": 0, "noop": 0}
    pac.apply(plan)
    assert {p["name"] for p in fake.policies.values()} == {"sales_read", "hr_read"}
    again = pac.plan(specs)
    assert again.empty, again.render()


def test_update_shows_field_diff(fake):
    fake.add_policy(service="cm_hive", name="sales_read", description="Analysts read sales",
                    resources={"database": {"values": ["sales"]}, "table": {"values": ["*"]},
                               "column": {"values": ["*"]}},
                    policyItems=[{"groups": ["analyst"], "accesses": [{"type": "select", "isAllowed": True},
                                                                      {"type": "update", "isAllowed": True}]}])
    spec = parse_spec({"service": "cm_hive", "policies": [
        {"name": "sales_read", "description": "Analysts read sales",
         "resources": {"database": "sales", "table": "*", "column": "*"},
         "allow": [{"groups": ["analyst"], "accesses": ["select"]}]}]})
    plan = _pac().plan(spec)
    (change,) = plan.pending
    assert change.action == "update" and [d[0] for d in change.diff] == ["policyItems"]
    assert "~ policy cm_hive/sales_read" in plan.render()


def test_order_and_defaults_do_not_cause_diff(fake):
    fake.add_policy(service="cm_hive", name="p", isAuditEnabled=True, policyType=0, policyPriority=0,
                    resources={"database": {"values": ["b", "a"], "isExcludes": False, "isRecursive": False}},
                    policyItems=[{"users": ["u2", "u1"], "accesses": [{"type": "b", "isAllowed": True},
                                                                      {"type": "a", "isAllowed": True}],
                                  "delegateAdmin": False}],
                    guid="g", createTime=1, updatedBy="admin", resourceSignature="x", serviceType="hive")
    spec = parse_spec({"service": "cm_hive", "policies": [
        {"name": "p", "resources": {"database": ["a", "b"]}, "allow": [{"users": ["u1", "u2"], "accesses": ["a", "b"]}]}]})
    assert _pac().plan(spec).empty


def test_prune_respects_managed_label(fake):
    fake.add_policy(service="cm_hive", name="manual", resources={}, policyLabels=[])
    fake.add_policy(service="cm_hive", name="old", resources={}, policyLabels=["gitops"])
    spec = parse_spec({"service": "cm_hive", "prune": True, "managed_label": "gitops", "policies": []})
    plan = _pac().plan(spec)
    assert [(c.action, c.name) for c in plan.pending] == [("delete", "old")]


def test_no_prune_by_default(fake):
    fake.add_policy(service="cm_hive", name="other", resources={})
    spec = parse_spec({"service": "cm_hive", "policies": []})
    assert _pac().plan(spec).empty


def test_apply_detects_concurrent_change(fake):
    p = fake.add_policy(service="cm_hive", name="p", description="old", resources={})
    spec = parse_spec({"service": "cm_hive", "policies": [{"name": "p", "description": "new", "resources": {}}]})
    pac = _pac()
    plan = pac.plan(spec)
    p["version"] += 1  # someone edits the policy between plan and apply
    with pytest.raises(RangerConflictException):
        pac.apply(plan)


def test_export_round_trips(fake):
    fake.add_policy(service="cm_hive", name="p", description="d",
                    resources={"database": {"values": ["sales"]}, "table": {"values": ["a", "b"]}},
                    policyItems=[{"groups": ["g"], "accesses": [{"type": "select", "isAllowed": True}]}],
                    guid="x", createTime=5)
    pac = _pac()
    doc = pac.export("cm_hive")
    assert doc["policies"][0] == {"name": "p", "description": "d",
                                  "resources": {"database": "sales", "table": ["a", "b"]},
                                  "allow": [{"accesses": ["select"], "groups": ["g"]}]}
    assert "guid" not in dump_spec(doc)
    assert pac.plan(parse_spec(doc)).empty


def test_spec_validation():
    with pytest.raises(ValueError, match="duplicate"):
        parse_spec({"service": "s", "policies": [{"name": "a"}, {"name": "a"}]})
    with pytest.raises(ValueError, match="unknown top-level"):
        parse_spec({"service": "s", "polices": []})
    with pytest.raises(ValueError, match="no service"):
        parse_spec({"policies": [{"name": "a"}]})
