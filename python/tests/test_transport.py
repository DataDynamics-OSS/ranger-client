import json
import logging

import httpx
import pytest
import respx

from conftest import ADMIN, ADMIN2
from ranger_sdk import RangerAdmin, RetryPolicy
from ranger_sdk._transport.core import Endpoints, mask_body, mask_headers
from ranger_sdk.auth import AuthProvider, Basic, Jwt, TrustedHeader
from ranger_sdk.errors import (
    RangerAuthenticationException,
    RangerServerException,
    RangerTransportException,
)

POLICY = "/service/public/v2/api/policy/1"
FAST = RetryPolicy(max_attempts=3, base_delay=0, jitter=False)


def test_failover_to_second_node_on_connect_error():
    with respx.mock() as m:
        m.get(ADMIN + POLICY).mock(side_effect=httpx.ConnectError("refused"))
        m.get(ADMIN2 + POLICY).respond(200, json={"id": 1})
        admin = RangerAdmin([ADMIN, ADMIN2], retry=FAST)
        assert admin.policies.get(1).id == 1
        # node 1 is cooling down: the next call goes straight to node 2
        assert admin.policies.get(1).id == 1
        assert m.calls.call_count == 3


def test_failover_on_5xx_and_cooldown_recovery(monkeypatch):
    t = [1000.0]
    monkeypatch.setattr("ranger_sdk._transport.core.time.monotonic", lambda: t[0])
    ep = Endpoints([ADMIN, ADMIN2], cooldown=30)
    assert ep.pick() == ADMIN
    ep.mark_down(ADMIN)
    assert ep.pick() == ADMIN2
    assert ep.pick() == ADMIN2
    t[0] += 31
    assert ADMIN in {ep.pick(), ep.pick()}


def test_all_nodes_down_raises_transport_error():
    with respx.mock() as m:
        m.get(url__regex=r".*").mock(side_effect=httpx.ConnectError("refused"))
        admin = RangerAdmin([ADMIN, ADMIN2], retry=FAST)
        with pytest.raises(RangerTransportException) as ei:
            admin.policies.get(1)
        assert ei.value.request_id
        assert m.calls.call_count == 3


def test_4xx_is_not_retried():
    with respx.mock() as m:
        route = m.get(ADMIN + POLICY).respond(400, json={"msgDesc": "bad"})
        with pytest.raises(Exception):
            RangerAdmin(ADMIN, retry=FAST).policies.get(1)
        assert route.call_count == 1


def test_retry_after_is_honoured(monkeypatch):
    slept = []
    monkeypatch.setattr("ranger_sdk._transport.sync.time.sleep", slept.append)
    with respx.mock() as m:
        m.get(ADMIN + POLICY).mock(side_effect=[httpx.Response(429, headers={"Retry-After": "2"}),
                                                httpx.Response(200, json={"id": 1})])
        RangerAdmin(ADMIN, retry=RetryPolicy(max_attempts=2)).policies.get(1)
    assert slept == [2.0]


def test_server_error_after_retries():
    with respx.mock() as m:
        route = m.get(ADMIN + POLICY).respond(500, text="boom")
        with pytest.raises(RangerServerException) as ei:
            RangerAdmin(ADMIN, retry=FAST).policies.get(1)
        assert route.call_count == 3
        assert ei.value.status == 500 and "GET" in str(ei.value)


def test_jwt_refreshes_once_on_401():
    tokens = iter(["t1", "t2"])
    with respx.mock() as m:
        route = m.get(ADMIN + POLICY).mock(side_effect=[httpx.Response(401), httpx.Response(200, json={"id": 1})])
        RangerAdmin(ADMIN, auth=Jwt(lambda: next(tokens)), retry=FAST).policies.get(1)
        assert [c.request.headers["authorization"] for c in route.calls] == ["Bearer t1", "Bearer t2"]


def test_401_after_refresh_raises():
    with respx.mock() as m:
        m.get(ADMIN + POLICY).respond(401)
        with pytest.raises(RangerAuthenticationException):
            RangerAdmin(ADMIN, auth=Jwt(lambda: "t"), retry=FAST).policies.get(1)


def test_jwt_prerefresh_before_expiry(monkeypatch):
    import time as _t

    now = _t.time()
    issued = []

    def source():
        issued.append(1)
        return (f"tok{len(issued)}", now + 30)  # expires within the 60 s skew -> always refreshed

    jwt = Jwt(source)
    assert jwt.headers("h")["Authorization"] == "Bearer tok1"
    assert jwt.headers("h")["Authorization"] == "Bearer tok2"


class _Negotiate(AuthProvider):
    supports_session_reuse = True

    def __init__(self):
        self.calls = 0

    def headers(self, host):
        self.calls += 1
        return {"Authorization": "Negotiate abc"}

    def refresh(self):
        return True


def test_session_cookie_skips_negotiation():
    auth = _Negotiate()
    with respx.mock() as m:
        route = m.get(ADMIN + POLICY).mock(side_effect=[
            httpx.Response(200, json={"id": 1}, headers={"Set-Cookie": "RANGERADMINSESSIONID=s1; Path=/"}),
            httpx.Response(200, json={"id": 1}),
        ])
        admin = RangerAdmin(ADMIN, auth=auth, retry=FAST)
        admin.policies.get(1)
        admin.policies.get(1)
        assert auth.calls == 1
        assert "authorization" not in route.calls[1].request.headers
        assert "RANGERADMINSESSIONID=s1" in route.calls[1].request.headers["cookie"]


def test_trusted_header_is_pdp_only():
    with pytest.raises(ValueError):
        RangerAdmin(ADMIN, auth=TrustedHeader("svc"))


def test_masking():
    assert mask_headers({"Authorization": "Basic x", "X": "y"}) == {"Authorization": "***", "X": "y"}
    assert mask_body('{"password":"p","name":"n"}') == '{"password":"***","name":"n"}'
    assert "secret" not in repr(Basic("admin", "secret"))


def test_debug_log_masks_credentials(caplog):
    caplog.set_level(logging.DEBUG, logger="ranger_sdk")
    with respx.mock() as m:
        m.get(ADMIN + POLICY).respond(200, json={"id": 1})
        RangerAdmin(ADMIN, auth=Basic("admin", "s3cret")).policies.get(1)
    assert "s3cret" not in caplog.text and "YWRtaW46czNjcmV0" not in caplog.text


def test_insecure_tls_warns():
    with pytest.warns(UserWarning):
        RangerAdmin(ADMIN, verify=False)


def test_hooks_receive_events():
    events = []
    with respx.mock() as m:
        m.get(ADMIN + POLICY).respond(200, json={"id": 1})
        RangerAdmin(ADMIN, hooks=[lambda e, info: events.append((e, info.get("status")))]).policies.get(1)
    assert events == [("request", None), ("response", 200)]


def test_request_id_is_stable_across_retries():
    with respx.mock() as m:
        route = m.get(ADMIN + POLICY).mock(side_effect=[httpx.Response(503), httpx.Response(200, json={"id": 1})])
        RangerAdmin(ADMIN, retry=FAST).policies.get(1)
        ids = {c.request.headers["x-request-id"] for c in route.calls}
        assert len(ids) == 1


def test_interceptors_see_request_and_response_bodies():
    events = []

    class Bodies:
        def begin(self, server, method, url, attempt, request_id, set_header):
            class A:
                def on_request_body(self, content_type, body):
                    events.append(("request", content_type, body))

                def on_response_body(self, status, body):
                    events.append(("response", status, body))

                def on_response(self, status):
                    events.append(("status", status))

                def on_error(self, error):
                    events.append(("error",))
            return A()

    with respx.mock() as mock:
        mock.route(host="ranger1").mock(return_value=httpx.Response(200, json={"id": 1, "name": "s"}))
        RangerAdmin(ADMIN, interceptors=[Bodies()]).services.create({"name": "s", "type": "hive"})
    assert events[0][:2] == ("request", "application/json") and json.loads(events[0][2]) == {"name": "s", "type": "hive"}
    assert events[1][:2] == ("response", 200) and json.loads(events[1][2]) == {"id": 1, "name": "s"}
    assert events[2] == ("status", 200)
