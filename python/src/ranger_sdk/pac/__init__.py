"""Policy-as-code: declarative YAML specs -> Plan (diff) -> Apply, plus Export.

Spec format (one or more YAML documents per file, any number of files)::

    service: cm_hive          # required for policies
    zone: finance             # optional security zone
    prune: false              # delete server policies missing from the spec
    managed_label: gitops     # optional: tag applied policies, and prune only tagged ones
    policies:
      - name: sales_read
        description: Analysts read sales
        resources: {database: sales, table: "*", column: "*"}
        allow:
          - groups: [analyst]
            accesses: [select]
    roles:
      - name: analysts
        users: [alice, bob]
        groups: [analyst]

Policy entries accept every field of :class:`ranger_sdk.models.Policy` (snake
or camelCase), plus the short forms ``allow``/``deny``/``enabled``/``labels``.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple, Union

import yaml

from ranger_sdk._flow import Flow, flow
from ranger_sdk.admin._resources import Policies, Roles
from ranger_sdk.models import Policy, Role

# --------------------------------------------------------------------------- spec


@dataclass
class PolicySet:
    service: Optional[str] = None
    zone: Optional[str] = None
    prune: bool = False
    managed_label: Optional[str] = None
    policies: List[Policy] = field(default_factory=list)
    roles: List[Role] = field(default_factory=list)
    source: str = "<memory>"


_SHORT = {"enabled": "isEnabled", "labels": "policyLabels", "audit": "isAuditEnabled", "priority": "policyPriority"}


def _policy_from_spec(raw: Dict[str, Any], ps: PolicySet) -> Policy:
    data = {(_SHORT.get(k, k)): v for k, v in raw.items()}
    data.setdefault("service", ps.service)
    if ps.zone:
        data.setdefault("zoneName", ps.zone)
    if not data.get("service"):
        raise ValueError(f"{ps.source}: policy {raw.get('name')!r} has no service")
    if not data.get("name"):
        raise ValueError(f"{ps.source}: a policy is missing 'name'")
    p = Policy.model_validate(data)
    if ps.managed_label and ps.managed_label not in (p.policy_labels or []):
        p = p.model_copy(update={"policy_labels": [*(p.policy_labels or []), ps.managed_label]})
    return p


def parse_spec(doc: Dict[str, Any], source: str = "<memory>") -> PolicySet:
    if not isinstance(doc, dict):
        raise ValueError(f"{source}: a spec document must be a mapping")
    known = {"service", "zone", "prune", "managed_label", "policies", "roles", "apiVersion", "kind"}
    unknown = set(doc) - known
    if unknown:
        raise ValueError(f"{source}: unknown top-level keys {sorted(unknown)}")
    ps = PolicySet(service=doc.get("service"), zone=doc.get("zone"), prune=bool(doc.get("prune", False)),
                   managed_label=doc.get("managed_label"), source=source)
    ps.policies = [_policy_from_spec(p, ps) for p in doc.get("policies") or []]
    ps.roles = [Role.model_validate(r) for r in doc.get("roles") or []]
    names = [p.name for p in ps.policies]
    dups = sorted({n for n in names if names.count(n) > 1})
    if dups:
        raise ValueError(f"{source}: duplicate policy names {dups}")
    return ps


def load_specs(paths: Union[str, Sequence[str]]) -> List[PolicySet]:
    """Load every ``*.yaml``/``*.yml`` file (directories are walked, sorted)."""
    if isinstance(paths, str):
        paths = [paths]
    files: List[str] = []
    for p in paths:
        if os.path.isdir(p):
            for root, _, names in sorted(os.walk(p)):
                files += [os.path.join(root, n) for n in sorted(names) if n.endswith((".yaml", ".yml"))]
        else:
            files.append(p)
    out: List[PolicySet] = []
    for f in files:
        with open(f, encoding="utf-8") as fh:
            for i, doc in enumerate(yaml.safe_load_all(fh)):
                if doc:
                    out.append(parse_spec(doc, f"{f}#{i}" if i else f))
    return out


# --------------------------------------------------------------------------- normalisation

_POLICY_DEFAULTS: Dict[str, Any] = {
    "isEnabled": True, "isAuditEnabled": True, "policyType": 0, "policyPriority": 0, "isDenyAllElse": False,
    "description": "",
}
_COMPARED = ["description", "isEnabled", "isAuditEnabled", "policyType", "policyPriority", "isDenyAllElse",
             "resources", "additionalResources", "conditions", "policyItems", "denyPolicyItems", "allowExceptions",
             "denyExceptions", "dataMaskPolicyItems", "rowFilterPolicyItems", "policyLabels", "validitySchedules"]
_UNORDERED_ITEMS = {"policyItems", "denyPolicyItems", "allowExceptions", "denyExceptions"}


def _norm_resource(r: Dict[str, Any]) -> Dict[str, Any]:
    return {"values": sorted(r.get("values") or []), "isExcludes": bool(r.get("isExcludes")),
            "isRecursive": bool(r.get("isRecursive"))}


def _norm_item(item: Dict[str, Any]) -> Dict[str, Any]:
    out = dict(item)
    for k in ("users", "groups", "roles"):
        out[k] = sorted(out.get(k) or [])
    out["accesses"] = sorted(({"type": a["type"], "isAllowed": a.get("isAllowed", True)}
                              for a in out.get("accesses") or []), key=lambda a: a["type"])
    out["conditions"] = sorted(({"type": c["type"], "values": sorted(c.get("values") or [])}
                                for c in out.get("conditions") or []), key=lambda c: c["type"])
    out["delegateAdmin"] = bool(out.get("delegateAdmin"))
    return out


def normalize_policy(p: Policy) -> Dict[str, Any]:
    """The semantically compared view of a policy (server bookkeeping stripped, defaults applied)."""
    raw = p.to_api()
    out: Dict[str, Any] = {}
    for key in _COMPARED:
        v = raw.get(key)
        if v in (None, [], {}) and key in _POLICY_DEFAULTS:
            v = _POLICY_DEFAULTS[key]
        if key == "resources":
            v = {k: _norm_resource(r) for k, r in sorted((v or {}).items())}
        elif key == "additionalResources":
            v = [{k: _norm_resource(r) for k, r in sorted(m.items())} for m in v or []]
        elif key in _UNORDERED_ITEMS:
            v = sorted((_norm_item(i) for i in v or []), key=lambda i: json.dumps(i, sort_keys=True))
        elif key in ("dataMaskPolicyItems", "rowFilterPolicyItems"):
            v = [_norm_item(i) for i in v or []]  # evaluation order matters
        elif key == "policyLabels":
            v = sorted(v or [])
        elif key in ("conditions", "validitySchedules"):
            v = v or []
        out[key] = v
    return out


def normalize_role(r: Role) -> Dict[str, Any]:
    raw = r.to_api()

    def members(k: str) -> List[Dict[str, Any]]:
        return sorted(({"name": m["name"], "isAdmin": bool(m.get("isAdmin"))} for m in raw.get(k) or []),
                      key=lambda m: m["name"])

    return {"description": raw.get("description") or "", "users": members("users"), "groups": members("groups"),
            "roles": members("roles")}


def _diff(before: Dict[str, Any], after: Dict[str, Any]) -> List[Tuple[str, Any, Any]]:
    return [(k, before.get(k), after.get(k)) for k in after if before.get(k) != after.get(k)]


# --------------------------------------------------------------------------- plan


@dataclass
class Change:
    action: str  # create | update | delete | noop
    kind: str  # policy | role
    name: str
    service: Optional[str] = None
    zone: Optional[str] = None
    desired: Any = None
    current: Any = None
    diff: List[Tuple[str, Any, Any]] = field(default_factory=list)

    @property
    def key(self) -> str:
        scope = "/".join(x for x in (self.service, self.zone) if x)
        return f"{self.kind} {scope + '/' if scope else ''}{self.name}"


@dataclass
class Plan:
    changes: List[Change] = field(default_factory=list)

    @property
    def pending(self) -> List[Change]:
        return [c for c in self.changes if c.action != "noop"]

    def summary(self) -> Dict[str, int]:
        out = {"create": 0, "update": 0, "delete": 0, "noop": 0}
        for c in self.changes:
            out[c.action] += 1
        return out

    @property
    def empty(self) -> bool:
        return not self.pending

    def render(self, color: bool = False) -> str:
        sym = {"create": "+", "update": "~", "delete": "-"}
        paint = {"create": "\033[32m", "update": "\033[33m", "delete": "\033[31m"}
        lines: List[str] = []
        for c in self.pending:
            head = f"{sym[c.action]} {c.key}"
            lines.append(f"{paint[c.action]}{head}\033[0m" if color else head)
            for fld, before, after in c.diff:
                lines.append(f"    {fld}: {_short(before)} -> {_short(after)}")
        s = self.summary()
        lines.append(f"Plan: {s['create']} to create, {s['update']} to update, {s['delete']} to delete, "
                     f"{s['noop']} unchanged.")
        return "\n".join(lines)

    def to_dict(self) -> Dict[str, Any]:
        return {"summary": self.summary(),
                "changes": [{"action": c.action, "kind": c.kind, "service": c.service, "zone": c.zone,
                             "name": c.name, "diff": [{"field": f, "before": b, "after": a} for f, b, a in c.diff]}
                            for c in self.pending]}


def _short(v: Any, limit: int = 120) -> str:
    s = json.dumps(v, sort_keys=True, ensure_ascii=False)
    return s if len(s) <= limit else s[: limit - 3] + "..."


class PolicyAsCode:
    """Plan/apply engine bound to a :class:`RangerAdmin` or :class:`AsyncRangerAdmin`."""

    def __init__(self, admin: Any) -> None:
        self._admin = admin
        self._runner = admin._runner

    @flow
    def plan(self, specs: Union[PolicySet, Sequence[PolicySet]], prune: Optional[bool] = None) -> Flow[Plan]:
        specs = [specs] if isinstance(specs, PolicySet) else list(specs)
        plan = Plan()
        policies, roles = self._admin.policies, self._admin.roles

        # group desired policies by (service, zone)
        scopes: Dict[Tuple[str, Optional[str]], List[Tuple[Policy, PolicySet]]] = {}
        for ps in specs:
            for p in ps.policies:
                scopes.setdefault((p.service, p.zone_name), []).append((p, ps))
            if ps.service and not ps.policies:
                scopes.setdefault((ps.service, ps.zone), [])

        for (service, zone), entries in scopes.items():
            current_list: List[Policy] = []
            start = 0
            while True:  # fetch all server policies of this scope once
                page = yield from Policies.search.flow(policies, service, zone=zone, start_index=start, page_size=500)
                current_list += page
                if len(page) < 500:
                    break
                start += len(page)
            current = {p.name: p for p in current_list if (p.zone_name or None) == (zone or None)}
            seen = set()
            for desired, _ in entries:
                seen.add(desired.name)
                cur = current.get(desired.name)
                if cur is None:
                    plan.changes.append(Change("create", "policy", desired.name, service, zone, desired,
                                               diff=_diff({}, normalize_policy(desired))))
                    continue
                d = _diff(normalize_policy(cur), normalize_policy(desired))
                plan.changes.append(Change("update" if d else "noop", "policy", desired.name, service, zone,
                                           desired, cur, d))
            spec_sets = {id(ps): ps for _, ps in entries}.values() or [
                ps for ps in specs if ps.service == service and ps.zone == zone]
            do_prune = prune if prune is not None else any(ps.prune for ps in spec_sets)
            labels = {ps.managed_label for ps in spec_sets if ps.managed_label}
            if do_prune:
                for name, cur in sorted(current.items()):
                    if name in seen:
                        continue
                    if labels and not labels & set(cur.policy_labels or []):
                        continue  # not managed by this spec
                    plan.changes.append(Change("delete", "policy", name, service, zone, None, cur))

        for ps in specs:
            for role in ps.roles:
                cur_role = yield from Roles.find.flow(roles, role.name)
                if cur_role is None:
                    plan.changes.append(Change("create", "role", role.name, desired=role,
                                               diff=_diff({}, normalize_role(role))))
                else:
                    d = _diff(normalize_role(cur_role), normalize_role(role))
                    plan.changes.append(Change("update" if d else "noop", "role", role.name, desired=role,
                                               current=cur_role, diff=d))
        return plan

    @flow
    def apply(self, plan: Plan) -> Flow[List[Change]]:
        """Execute a plan. Updates carry the version seen at plan time, so a policy
        changed on the server in between fails with ``RangerConflictException``.
        Role changes run first: Ranger rejects a policy that names a role which does not exist yet."""
        policies, roles = self._admin.policies, self._admin.roles
        done: List[Change] = []
        ordered = [c for c in plan.pending if c.kind == "role"] + [c for c in plan.pending if c.kind != "role"]
        for c in ordered:
            if c.kind == "policy":
                if c.action == "create":
                    yield from Policies.create.flow(policies, c.desired)
                elif c.action == "update":
                    merged = c.desired.model_copy(update={"id": c.current.id, "guid": c.current.guid,
                                                          "version": c.current.version})
                    yield from Policies.update.flow(policies, merged, check_version=True)
                elif c.action == "delete":
                    yield from Policies.delete.flow(policies, c.current.id)
            elif c.kind == "role":
                if c.action == "create":
                    yield from Roles.create.flow(roles, c.desired)
                elif c.action == "update":
                    yield from Roles.update.flow(roles, c.desired.model_copy(update={"id": c.current.id}))
            done.append(c)
        return done

    @flow
    def export(self, service: str, zone: Optional[str] = None,
               include_disabled: bool = True) -> Flow[Dict[str, Any]]:
        """Reverse-engineer a spec document from the server's current policies."""
        out: List[Dict[str, Any]] = []
        start = 0
        while True:
            page = yield from Policies.search.flow(self._admin.policies, service, zone=zone, start_index=start,
                                                   page_size=500)
            out += [policy_to_spec(p) for p in page if include_disabled or p.is_enabled is not False]
            if len(page) < 500:
                break
            start += len(page)
        doc: Dict[str, Any] = {"service": service}
        if zone:
            doc["zone"] = zone
        doc["policies"] = sorted(out, key=lambda p: p["name"])
        return doc


def _compact_resource(r: Dict[str, Any]) -> Any:
    if r.get("isExcludes") or r.get("isRecursive"):
        return {k: v for k, v in r.items() if v}
    vals = r.get("values") or []
    return vals[0] if len(vals) == 1 else vals


def _compact_item(item: Dict[str, Any]) -> Dict[str, Any]:
    out = {k: v for k, v in item.items() if v not in (None, [], False)}
    acc = item.get("accesses") or []
    if all(a.get("isAllowed", True) for a in acc):
        out["accesses"] = [a["type"] for a in acc]
    return out


def policy_to_spec(p: Policy) -> Dict[str, Any]:
    raw = p.to_api()
    for k in ("id", "guid", "version", "createTime", "updateTime", "createdBy", "updatedBy", "resourceSignature",
              "service", "serviceType", "zoneName"):
        raw.pop(k, None)
    out: Dict[str, Any] = {"name": raw.pop("name")}
    if raw.get("description"):
        out["description"] = raw["description"]
    raw.pop("description", None)
    if raw.pop("isEnabled", True) is False:
        out["enabled"] = False
    if raw.pop("isAuditEnabled", True) is False:
        out["audit"] = False
    if raw.get("policyType") in (0, None):
        raw.pop("policyType", None)
    if raw.get("policyPriority") in (0, None):
        raw.pop("policyPriority", None)
    if raw.get("isDenyAllElse") in (False, None):
        raw.pop("isDenyAllElse", None)
    out["resources"] = {k: _compact_resource(r) for k, r in (raw.pop("resources", None) or {}).items()}
    for src, dst in (("policyItems", "allow"), ("denyPolicyItems", "deny")):
        items = raw.pop(src, None)
        if items:
            out[dst] = [_compact_item(i) for i in items]
    for k in ("allowExceptions", "denyExceptions", "dataMaskPolicyItems", "rowFilterPolicyItems"):
        items = raw.pop(k, None)
        if items:
            out[k] = [_compact_item(i) for i in items]
    labels = raw.pop("policyLabels", None)
    if labels:
        out["labels"] = labels
    for k, v in raw.items():  # anything else (options, conditions, schedules, future fields)
        if v not in (None, [], {}):
            out[k] = v
    return out


def dump_spec(doc: Dict[str, Any]) -> str:
    return yaml.safe_dump(doc, sort_keys=False, allow_unicode=True, default_flow_style=None, width=120)


__all__ = ["PolicySet", "Plan", "Change", "PolicyAsCode", "load_specs", "parse_spec", "normalize_policy",
           "policy_to_spec", "dump_spec"]
