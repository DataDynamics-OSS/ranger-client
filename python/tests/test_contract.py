"""Runs the language-neutral scenarios in spec/contract against the Python SDK."""

import json
import os
from urllib.parse import parse_qs, unquote, urlsplit

import httpx
import pytest
import respx
import yaml

from conftest import ADMIN, KMS, PDP, SPEC_DIR
from ranger_sdk import RangerAdmin, RangerKms, RangerPdp, RetryPolicy, errors

with open(os.path.join(SPEC_DIR, "contract", "scenarios.yaml"), encoding="utf-8") as fh:
    SCENARIOS = yaml.safe_load(fh)["scenarios"]


def _subset(expected, actual, path="$"):
    if isinstance(expected, dict):
        assert isinstance(actual, dict), f"{path}: expected object, got {actual!r}"
        for k, v in expected.items():
            assert k in actual, f"{path}.{k} missing in {actual!r}"
            _subset(v, actual[k], f"{path}.{k}")
    elif isinstance(expected, list):
        assert isinstance(actual, list) and len(actual) == len(expected), f"{path}: {actual!r} != {expected!r}"
        for i, (e, a) in enumerate(zip(expected, actual)):
            _subset(e, a, f"{path}[{i}]")
    else:
        assert expected == actual, f"{path}: {actual!r} != {expected!r}"


def _invoke(op, args, **opts):
    opts.setdefault("retry", RetryPolicy(max_attempts=3, base_delay=0, jitter=False))
    group, _, name = op.partition(".")
    if group == "pdp":
        pdp = RangerPdp(PDP, **opts)
        return pdp.authorize(user=args["user"], service=args["service"], service_type=args["service_type"],
                             resource=[tuple(x) for x in args["resource"]], access=args["access"])
    if group == "kms":
        return getattr(RangerKms(KMS, **opts), name)(**args)
    admin = RangerAdmin(ADMIN, **opts)
    sub = getattr(admin, group)
    if op == "policies.search":
        return sub.search(args["service"], start_index=args["start_index"], page_size=args["page_size"])
    if op == "roles.grant":
        return sub.grant(args["service"], args["request"])
    return getattr(sub, name)(**args)


def _result_dict(result):
    data = result.to_api() if hasattr(result, "to_api") else result
    if hasattr(result, "decision") and result.decision is not None:
        data["decision"] = result.decision.value
        data["decision_raw"] = result.decision_raw
    return data


@pytest.mark.parametrize("sc", SCENARIOS, ids=[s["name"] for s in SCENARIOS])
def test_scenario(sc):
    base = PDP if sc["op"].startswith("pdp.") else KMS if sc["op"].startswith("kms.") else ADMIN
    responses = [httpx.Response(r["status"], json=r.get("body")) if "body" in r else httpx.Response(r["status"])
                 for r in sc["responses"]]
    opts = {"server_version": sc["server_version"]} if "server_version" in sc else {}
    with respx.mock(assert_all_called=False) as mock:
        route = mock.route(host=urlsplit(base).hostname).mock(side_effect=responses)
        if "expect_error" in sc:
            with pytest.raises(getattr(errors, sc["expect_error"])):
                _invoke(sc["op"], sc["args"], **opts)
        else:
            result = _invoke(sc["op"], sc["args"], **opts)
            if "expect_result" in sc:
                _subset(sc["expect_result"], _result_dict(result))
            if "expect_result_size" in sc:
                assert len(result) == sc["expect_result_size"]
        if "expect_request_count" in sc:
            assert route.call_count == sc["expect_request_count"]
        exp = sc.get("expect_request")
        if exp:
            req = route.calls[0].request
            assert req.method == exp["method"]
            assert req.url.raw_path.decode().split("?")[0] == exp["path"]
            if "query" in exp:
                q = {k: v[0] for k, v in parse_qs(urlsplit(str(req.url)).query).items()}
                _subset({k: str(v) for k, v in exp["query"].items()}, q)
            if "body" in exp:
                _subset(exp["body"], json.loads(req.content))
            for name, value in (exp.get("headers") or {}).items():
                assert req.headers.get(name) == value, f"header {name}: {req.headers.get(name)!r}"
            if exp.get("request_id_matches_body"):
                body_id = json.loads(req.content).get("requestId")
                assert body_id and req.headers.get("X-Request-Id") == body_id
