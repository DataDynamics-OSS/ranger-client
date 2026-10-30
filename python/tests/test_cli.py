import json

import respx
import yaml
from typer.testing import CliRunner

from conftest import ADMIN, PDP
from fake_ranger import FakeRanger
from ranger_sdk.cli.main import app

runner = CliRunner()


def _config(tmp_path, monkeypatch):
    cfg = {"profiles": {
        "prod": {"admin": {"urls": [ADMIN], "auth": {"type": "basic", "username": "admin",
                                                     "password_env": "RANGER_PW"}}},
        "pdp": {"pdp": {"urls": [PDP], "service_type": "hive",
                        "auth": {"type": "trusted_header", "user": "svc"}}}}}
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump(cfg))
    monkeypatch.setenv("RANGERCTL_CONFIG", str(path))
    monkeypatch.setenv("RANGER_PW", "pw")


def test_plan_apply_export(tmp_path, monkeypatch):
    _config(tmp_path, monkeypatch)
    spec = tmp_path / "policies"
    spec.mkdir()
    (spec / "hive.yaml").write_text(
        "service: cm_hive\npolicies:\n  - name: p1\n    resources: {database: sales}\n"
        "    allow: [{groups: [analyst], accesses: [select]}]\n")
    fake = FakeRanger()
    with respx.mock(base_url=ADMIN) as m:
        m.route().mock(side_effect=fake)
        r = runner.invoke(app, ["--profile", "prod", "plan", "-f", str(spec), "--detailed-exitcode"])
        assert r.exit_code == 2, r.output
        assert "+ policy cm_hive/p1" in r.output
        r = runner.invoke(app, ["--profile", "prod", "apply", "-f", str(spec), "--auto-approve"])
        assert r.exit_code == 0, r.output
        assert "Applied 1 change(s)." in r.output
        r = runner.invoke(app, ["--profile", "prod", "plan", "-f", str(spec), "--detailed-exitcode"])
        assert r.exit_code == 0, r.output
        r = runner.invoke(app, ["--profile", "prod", "policy", "export", "--service", "cm_hive"])
        assert yaml.safe_load(r.output)["policies"][0]["name"] == "p1"
        r = runner.invoke(app, ["--profile", "prod", "policy", "list", "-s", "cm_hive", "-o", "json"])
        assert json.loads(r.output)[0]["name"] == "p1"


def test_authz_check(tmp_path, monkeypatch):
    _config(tmp_path, monkeypatch)
    with respx.mock() as m:
        route = m.post(PDP + "/authz/v1/authorize").respond(200, json={
            "decision": "DENY", "permissions": {"select": {"access": {"decision": "DENY", "policy": {"id": 9}}}}})
        r = runner.invoke(app, ["--profile", "pdp", "authz", "check", "alice", "dev_hive",
                                "database=sales,table=orders", "select"])
        assert r.exit_code == 3 and "DENY (policy id=9)" in r.output
        sent = json.loads(route.calls[0].request.content)
        assert sent["access"]["resource"]["name"] == "table:sales/orders"
        assert sent["context"]["serviceType"] == "hive"
        assert route.calls[0].request.headers["x-forwarded-user"] == "svc"
