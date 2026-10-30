"""rangerctl: command line front-end of the Ranger SDK."""

from __future__ import annotations

import json
import sys
from typing import Any, Dict, List, Optional

try:
    import typer
except ImportError:  # pragma: no cover
    sys.stderr.write("rangerctl needs typer: pip install 'ranger-sdk[cli]'\n")
    raise SystemExit(1)

import yaml

from ranger_sdk import RangerAdmin, RangerKms, RangerPdp, __version__
from ranger_sdk.capabilities import Feature
from ranger_sdk.cli.config import ConfigError, client_options, load_config, section
from ranger_sdk.errors import RangerException
from ranger_sdk.pac import PolicyAsCode, dump_spec, load_specs, policy_to_spec

app = typer.Typer(add_completion=False, no_args_is_help=True, help="Apache Ranger command line client.")
policy_app = typer.Typer(no_args_is_help=True, help="Policies.")
service_app = typer.Typer(no_args_is_help=True, help="Services.")
role_app = typer.Typer(no_args_is_help=True, help="Roles.")
authz_app = typer.Typer(no_args_is_help=True, help="PDP authorization checks.")
kms_app = typer.Typer(no_args_is_help=True, help="Ranger KMS keys.")
app.add_typer(policy_app, name="policy")
app.add_typer(service_app, name="service")
app.add_typer(role_app, name="role")
app.add_typer(authz_app, name="authz")
app.add_typer(kms_app, name="kms")

_state: Dict[str, Any] = {"profile": None, "config": None}


@app.callback()
def _main(
    profile: Optional[str] = typer.Option(None, "--profile", "-p", help="Profile in the config file."),
    config: Optional[str] = typer.Option(None, "--config", help="Config file (default ~/.config/rangerctl/config.yaml)."),
) -> None:
    _state["profile"] = profile
    _state["config"] = config


def _section(server: str) -> Dict[str, Any]:
    return section(load_config(_state["config"]), _state["profile"], server)


def _admin() -> RangerAdmin:
    sec = _section("admin")
    return RangerAdmin(sec["urls"], server_version=sec.get("server_version"), **client_options(sec))


def _pdp() -> RangerPdp:
    sec = _section("pdp")
    return RangerPdp(sec["urls"], service=sec.get("service"), service_type=sec.get("service_type"),
                     **client_options(sec))


def _kms() -> RangerKms:
    sec = _section("kms")
    return RangerKms(sec["urls"], **client_options(sec))


def _out(data: Any, fmt: str) -> None:
    if fmt == "json":
        typer.echo(json.dumps(data, indent=2, ensure_ascii=False, default=str))
    elif fmt == "yaml":
        typer.echo(yaml.safe_dump(data, sort_keys=False, allow_unicode=True))
    else:
        raise typer.BadParameter(f"unknown output format {fmt}")


def _table(rows: List[List[Any]], header: List[str]) -> None:
    cols = [header] + [[("" if c is None else str(c)) for c in r] for r in rows]
    widths = [max(len(r[i]) for r in cols) for i in range(len(header))]
    for r in cols:
        typer.echo("  ".join(c.ljust(w) for c, w in zip(r, widths)).rstrip())


def _parse_resource(text: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for part in text.split(","):
        if "=" not in part:
            raise typer.BadParameter(f"resource must be key=value[,key=value]: {text}")
        k, v = part.split("=", 1)
        out[k.strip()] = v.strip()
    return out


# --------------------------------------------------------------------------- top level


@app.command()
def version() -> None:
    """Print the SDK version."""
    typer.echo(f"rangerctl {__version__}")


@app.command()
def capabilities() -> None:
    """Show which features the Admin server supports."""
    with _admin() as admin:
        rows = [[f.value, "yes" if admin.capabilities.supports(f) else "no"]
                for f in Feature if f not in (Feature.KMS, Feature.PDP)]
    _table(rows, ["FEATURE", "SUPPORTED"])


@app.command()
def plan(
    files: List[str] = typer.Option(..., "-f", "--file", help="Spec file or directory (repeatable)."),
    prune: Optional[bool] = typer.Option(None, "--prune/--no-prune", help="Override the specs' prune setting."),
    output: str = typer.Option("text", "-o", "--output", help="text | json"),
    detailed_exitcode: bool = typer.Option(False, "--detailed-exitcode", help="Exit 2 when changes are pending."),
) -> None:
    """Show what apply would change."""
    specs = load_specs(files)
    with _admin() as admin:
        p = PolicyAsCode(admin).plan(specs, prune=prune)
    if output == "json":
        _out(p.to_dict(), "json")
    else:
        typer.echo(p.render(color=sys.stdout.isatty()))
    if detailed_exitcode and not p.empty:
        raise typer.Exit(2)


@app.command()
def apply(
    files: List[str] = typer.Option(..., "-f", "--file", help="Spec file or directory (repeatable)."),
    prune: Optional[bool] = typer.Option(None, "--prune/--no-prune", help="Override the specs' prune setting."),
    auto_approve: bool = typer.Option(False, "--auto-approve", help="Do not ask for confirmation."),
) -> None:
    """Apply spec files to Ranger."""
    specs = load_specs(files)
    with _admin() as admin:
        pac = PolicyAsCode(admin)
        p = pac.plan(specs, prune=prune)
        typer.echo(p.render(color=sys.stdout.isatty()))
        if p.empty:
            return
        if not auto_approve and not typer.confirm("Apply these changes?"):
            raise typer.Exit(1)
        done = pac.apply(p)
    typer.echo(f"Applied {len(done)} change(s).")


# --------------------------------------------------------------------------- policy


@policy_app.command("list")
def policy_list(
    service: Optional[str] = typer.Option(None, "--service", "-s"),
    zone: Optional[str] = typer.Option(None, "--zone"),
    user: Optional[str] = typer.Option(None, "--user"),
    group: Optional[str] = typer.Option(None, "--group"),
    output: str = typer.Option("table", "-o", "--output", help="table | json | yaml"),
) -> None:
    """List policies."""
    with _admin() as admin:
        items = list(admin.policies.iter(service, zone=zone, user=user, group=group))
    if output == "table":
        _table([[p.id, p.service, p.name, p.is_enabled, p.zone_name] for p in items],
               ["ID", "SERVICE", "NAME", "ENABLED", "ZONE"])
    else:
        _out([p.to_api() for p in items], output)


@policy_app.command("get")
def policy_get(
    name: Optional[str] = typer.Argument(None),
    service: Optional[str] = typer.Option(None, "--service", "-s"),
    id: Optional[int] = typer.Option(None, "--id"),
    output: str = typer.Option("yaml", "-o", "--output", help="json | yaml | spec"),
) -> None:
    """Show one policy (by --id, or by --service and NAME)."""
    with _admin() as admin:
        if id is not None:
            p = admin.policies.get(id)
        elif service and name:
            p = admin.policies.get_by_name(service, name)
        else:
            raise typer.BadParameter("pass --id, or --service and NAME")
    if output == "spec":
        typer.echo(dump_spec(policy_to_spec(p)))
    else:
        _out(p.to_api(), output)


@policy_app.command("export")
def policy_export(
    service: str = typer.Option(..., "--service", "-s"),
    zone: Optional[str] = typer.Option(None, "--zone"),
) -> None:
    """Export a service's policies as a spec file (YAML on stdout)."""
    with _admin() as admin:
        doc = PolicyAsCode(admin).export(service, zone)
    typer.echo(dump_spec(doc), nl=False)


@policy_app.command("delete")
def policy_delete(
    name: str = typer.Argument(...),
    service: str = typer.Option(..., "--service", "-s"),
    zone: Optional[str] = typer.Option(None, "--zone"),
    yes: bool = typer.Option(False, "--yes", "-y"),
) -> None:
    """Delete a policy by service and name."""
    if not yes and not typer.confirm(f"Delete policy {service}/{name}?"):
        raise typer.Exit(1)
    with _admin() as admin:
        admin.policies.delete_by_name(service, name, zone)
    typer.echo(f"Deleted {service}/{name}")


# --------------------------------------------------------------------------- service / role


@service_app.command("list")
def service_list(
    service_type: Optional[str] = typer.Option(None, "--type"),
    output: str = typer.Option("table", "-o", "--output"),
) -> None:
    """List services."""
    with _admin() as admin:
        items = list(admin.services.iter(service_type=service_type))
    if output == "table":
        _table([[s.id, s.type, s.name, s.is_enabled] for s in items], ["ID", "TYPE", "NAME", "ENABLED"])
    else:
        _out([s.to_api() for s in items], output)


@role_app.command("list")
def role_list(output: str = typer.Option("table", "-o", "--output")) -> None:
    """List roles."""
    with _admin() as admin:
        items = list(admin.roles.iter())
    if output == "table":
        _table([[r.id, r.name, ",".join(m.name for m in r.users or []), ",".join(m.name for m in r.groups or [])]
                for r in items], ["ID", "NAME", "USERS", "GROUPS"])
    else:
        _out([r.to_api() for r in items], output)


# --------------------------------------------------------------------------- authz / kms


@authz_app.command("check")
def authz_check(
    user: str = typer.Argument(...),
    service: str = typer.Argument(..., help="Ranger service name."),
    resource: str = typer.Argument(..., help="key=value[,key=value], top of the hierarchy first."),
    access: str = typer.Argument(..., help="Permission, e.g. select."),
    service_type: Optional[str] = typer.Option(None, "--service-type", help="Defaults to the profile's or SERVICE."),
    groups: Optional[str] = typer.Option(None, "--groups", help="Comma separated."),
    output: str = typer.Option("text", "-o", "--output", help="text | json"),
) -> None:
    """Ask the PDP whether USER may ACCESS RESOURCE. Exit code 0 = allowed, 3 = not allowed."""
    with _pdp() as pdp:
        stype = service_type or pdp._defaults.get("service_type") or service
        res = pdp.authorize(user=user, service=service, service_type=stype, resource=_parse_resource(resource),
                            access=access, groups=groups.split(",") if groups else None)
    if output == "json":
        _out(res.to_api(), "json")
    else:
        perm = (res.permissions or {}).get(access)
        policy = perm.access.policy if perm and perm.access else None
        decision = res.decision.value if res.decision else "UNKNOWN"
        typer.echo(f"{decision}" + (f" (policy id={policy.id})" if policy and policy.id is not None else ""))
    if not res.allowed:
        raise typer.Exit(3)


@kms_app.command("keys")
def kms_keys() -> None:
    """List key names."""
    with _kms() as kms:
        for name in kms.names():
            typer.echo(name)


def run() -> None:
    try:
        app()
    except (ConfigError, RangerException, ValueError, OSError) as exc:
        typer.echo(f"error: {exc}", err=True)
        raise SystemExit(1)


if __name__ == "__main__":  # pragma: no cover
    run()
