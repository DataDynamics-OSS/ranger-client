import filecmp
import os

import httpx
import pytest
import respx

from conftest import ADMIN, PDP, SPEC_DIR
from fake_ranger import FakeRanger
from ranger_sdk import AsyncRangerAdmin, AsyncRangerPdp, Feature, RangerAdmin, RangerPdp, UnsupportedFeatureException
from ranger_sdk.models import Policy
from ranger_sdk.pac import PolicyAsCode, parse_spec


async def test_async_admin_apply_and_iter():
    fake = FakeRanger()
    for i in range(5):
        fake.add_policy(service="s", name=f"p{i}", resources={})
    with respx.mock(base_url=ADMIN) as m:
        m.route().mock(side_effect=fake)
        async with AsyncRangerAdmin(ADMIN) as admin:
            names = [p.name async for p in admin.policies.iter("s", page_size=2)]
            assert names == [f"p{i}" for i in range(5)]
            created = await admin.policies.apply(Policy(service="s", name="new", resources={"db": "x"}))
            assert created.id and fake.policies[created.id]["name"] == "new"
            plan = await PolicyAsCode(admin).plan(parse_spec({"service": "s", "policies": [
                {"name": "new", "resources": {"db": "x"}}]}))
            assert plan.empty


async def test_async_pdp_multi():
    body = {"decision": "PARTIAL", "accesses": [{"decision": "ALLOW"}, {"decision": "DENY"}]}
    with respx.mock() as m:
        route = m.post(PDP + "/authz/v1/authorizeMulti").respond(200, json=body)
        async with AsyncRangerPdp(PDP, service="dev_hive", service_type="hive") as pdp:
            res = await pdp.authorize_multi(user="alice", accesses=[({"database": "a"}, "select"),
                                                                    ({"database": "b"}, ["select", "update"])])
        sent = route.calls[0].request
        import json
        payload = json.loads(sent.content)
        assert payload["context"] == {**payload["context"], "serviceName": "dev_hive", "serviceType": "hive"}
        assert [a["resource"]["name"] for a in payload["accesses"]] == ["database:a", "database:b"]
        assert [a.allowed for a in res.accesses] == [True, False]


def test_pdp_cache_and_retry():
    with respx.mock() as m:
        route = m.post(PDP + "/authz/v1/authorize").mock(side_effect=[
            httpx.Response(503), httpx.Response(200, json={"decision": "ALLOW"})])
        from ranger_sdk import RetryPolicy
        pdp = RangerPdp(PDP, service="s", service_type="hive", cache_ttl=60,
                        retry=RetryPolicy(max_attempts=3, base_delay=0, jitter=False))
        assert pdp.is_allowed("alice", {"database": "d"}, "select")
        assert pdp.is_allowed("alice", {"database": "d"}, "select")  # cache hit
        assert route.call_count == 2  # one retry, then served from cache


def test_capabilities_from_version():
    admin = RangerAdmin(ADMIN, server_version="2.9.0")
    assert admin.capabilities.supports(Feature.ZONES_V2)
    assert not admin.capabilities.supports(Feature.GDS)
    with pytest.raises(UnsupportedFeatureException, match="GDS"):
        admin.gds.datasets.get(1)


def test_capabilities_probe_is_cached():
    with respx.mock() as m:
        route = m.get(ADMIN + "/service/gds/dataset").respond(404)
        admin = RangerAdmin(ADMIN)
        assert not admin.capabilities.supports(Feature.GDS)
        assert not admin.capabilities.supports(Feature.GDS)
        assert route.call_count == 1
        m.get(ADMIN + "/service/public/v2/api/zones-v2").respond(403)
        assert admin.capabilities.supports(Feature.ZONES_V2)  # exists, caller lacks rights


def test_packaged_feature_matrix_matches_spec():
    import ranger_sdk

    packaged = os.path.join(os.path.dirname(ranger_sdk.__file__), "features.yaml")
    assert filecmp.cmp(packaged, os.path.join(SPEC_DIR, "features.yaml"), shallow=False), \
        "run: cp spec/features.yaml python/src/ranger_sdk/features.yaml"


def test_pdp_ready_is_not_retried():
    import httpx
    import respx

    from conftest import PDP
    from ranger_sdk import RangerPdp
    from ranger_sdk.errors import RangerServerException

    with respx.mock() as mock:
        route = mock.get(f"{PDP}/health/ready").mock(side_effect=[
            httpx.Response(200, json={"status": "READY", "ready": True}),
            httpx.Response(503, json={"status": "NOT_READY", "ready": False}),
        ])
        with RangerPdp(PDP) as pdp:
            assert pdp.ready()["status"] == "READY"
            with pytest.raises(RangerServerException):
                pdp.ready()
        assert route.call_count == 2
