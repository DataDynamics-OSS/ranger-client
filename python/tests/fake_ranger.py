"""A small stateful in-memory Ranger Admin for respx (policies + roles)."""

import itertools
import json
import re
from urllib.parse import unquote

import httpx

API = "/service/public/v2/api"


class FakeRanger:
    def __init__(self):
        self.policies = {}
        self.roles = {}
        self._ids = itertools.count(1)

    def add_policy(self, **p):
        p.setdefault("id", next(self._ids))
        p.setdefault("version", 1)
        p.setdefault("isEnabled", True)
        self.policies[p["id"]] = p
        return p

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = unquote(request.url.raw_path.decode().split("?")[0])
        q = dict(request.url.params)
        m = request.method
        body = json.loads(request.content) if request.content else None

        if path == f"{API}/policy" and m == "GET":
            items = [p for p in self.policies.values()
                     if ("serviceName" not in q or p["service"] == q["serviceName"])
                     and ("zoneName" not in q or p.get("zoneName") == q["zoneName"])]
            start, size = int(q.get("startIndex", 0)), int(q.get("pageSize", 200))
            return httpx.Response(200, json=items[start:start + size])
        if path == f"{API}/policy" and m == "POST":
            if any(p["service"] == body["service"] and p["name"] == body["name"] for p in self.policies.values()):
                return httpx.Response(400, json={"msgDesc": "policy-name already exists"})
            return httpx.Response(200, json=self.add_policy(**body))
        mo = re.fullmatch(rf"{API}/policy/(\d+)", path)
        if mo:
            pid = int(mo.group(1))
            if pid not in self.policies:
                return httpx.Response(404, json={"msgDesc": "not found"})
            if m == "GET":
                return httpx.Response(200, json=self.policies[pid])
            if m == "PUT":
                body["version"] = self.policies[pid]["version"] + 1
                body["id"] = pid
                self.policies[pid] = body
                return httpx.Response(200, json=body)
            if m == "DELETE":
                del self.policies[pid]
                return httpx.Response(204)
        mo = re.fullmatch(rf"{API}/service/([^/]+)/policy/(.+)", path)
        if mo and m == "GET":
            for p in self.policies.values():
                if p["service"] == mo.group(1) and p["name"] == mo.group(2):
                    return httpx.Response(200, json=p)
            return httpx.Response(404, json={"msgDesc": "not found"})
        mo = re.fullmatch(rf"{API}/roles/name/(.+)", path)
        if mo and m == "GET":
            r = self.roles.get(mo.group(1))
            return httpx.Response(200, json=r) if r else httpx.Response(404, json={"msgDesc": "no role"})
        if path == f"{API}/roles" and m == "POST":
            body["id"] = next(self._ids)
            self.roles[body["name"]] = body
            return httpx.Response(200, json=body)
        mo = re.fullmatch(rf"{API}/roles/(\d+)", path)
        if mo and m == "PUT":
            self.roles[body["name"]] = body
            return httpx.Response(200, json=body)
        return httpx.Response(404, json={"msgDesc": f"fake: no route {m} {path}"})
