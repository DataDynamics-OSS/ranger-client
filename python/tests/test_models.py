from ranger_sdk.models import (
    AuthzResult,
    Decision,
    Policy,
    PolicyItem,
    Role,
    from_official,
    resource_name,
)


def test_policy_shortcuts_and_wire_shape():
    p = Policy(service="s", name="n", resources={"database": "db", "table": ["a", "b"]},
               allow=[PolicyItem(groups=["g"], accesses=["select", "update"], conditions={"ip-range": "10.0.0.0/8"})],
               deny=[{"users": ["mallory"], "accesses": [{"type": "select"}]}])
    wire = p.to_api()
    assert wire["resources"] == {"database": {"values": ["db"]}, "table": {"values": ["a", "b"]}}
    assert wire["policyItems"][0]["accesses"] == [{"type": "select", "isAllowed": True},
                                                  {"type": "update", "isAllowed": True}]
    assert wire["policyItems"][0]["conditions"] == [{"type": "ip-range", "values": ["10.0.0.0/8"]}]
    assert wire["denyPolicyItems"][0]["users"] == ["mallory"]
    assert "allow" not in wire


def test_unknown_fields_round_trip():
    data = {"id": 1, "name": "n", "service": "s", "brandNewField": {"x": 1}, "policyItems": [
        {"accesses": [{"type": "select", "isAllowed": True}], "users": ["u"], "itemFuture": True}]}
    p = Policy.model_validate(data)
    assert p.extra_fields == {"brandNewField": {"x": 1}}
    out = p.to_api()
    assert out["brandNewField"] == {"x": 1}
    assert out["policyItems"][0]["itemFuture"] is True


def test_role_members_from_strings():
    r = Role(name="r", users=["alice"], groups=[{"name": "g", "isAdmin": True}])
    assert r.to_api()["users"] == [{"name": "alice", "isAdmin": False}]
    assert r.groups[0].is_admin


def test_resource_name():
    assert resource_name({"database": "sales", "table": "orders"}) == "table:sales/orders"
    assert resource_name([("path", "/a/b")]) == "path:\\/a\\/b"
    assert resource_name("table:x/y") == "table:x/y"


def test_open_enum_decision():
    r = AuthzResult.model_validate({"decision": "BRAND_NEW"})
    assert r.decision is Decision.UNKNOWN and r.decision_raw == "BRAND_NEW"
    assert AuthzResult.model_validate({"decision": "ALLOW"}).allowed


def test_from_official_bridge():
    class RangerPolicy(dict):  # stand-in for apache_ranger.model.ranger_policy.RangerPolicy
        pass

    p = from_official(RangerPolicy(service="s", name="n", isEnabled=True,
                                   resources={"database": {"values": ["d"], "isExcludes": False}}))
    assert isinstance(p, Policy) and p.is_enabled and p.resources["database"].values == ["d"]


def test_password_not_in_repr():
    from ranger_sdk.models import User

    assert "pw" not in repr(User(name="u", password="pw"))
