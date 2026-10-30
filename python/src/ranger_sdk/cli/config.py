"""rangerctl profiles.

``~/.config/rangerctl/config.yaml`` (or ``$RANGERCTL_CONFIG``)::

    default_profile: prod
    profiles:
      prod:
        admin:
          urls: [https://ranger1:6182, https://ranger2:6182]
          verify: /etc/pki/ranger-ca.pem
          server_version: "2.9.0"
          auth: {type: kerberos, principal: svc-argus@CORP.LOCAL, keytab: /etc/security/argus.keytab}
        kms:
          urls: [https://ranger-kms:9393]
          auth: {type: kerberos, principal: svc-argus@CORP.LOCAL, keytab: /etc/security/argus.keytab}
      pdp:
        pdp:
          urls: [https://ranger-pdp:6500]
          service_type: hive
          auth: {type: jwt, token_file: /run/secrets/pdp.jwt}

Secrets are never stored inline in the examples above: use ``password_env``,
``password_file``, ``token_env``, ``token_file`` or ``token_command``.
"""

from __future__ import annotations

import os
import shlex
import subprocess
from typing import Any, Dict, Optional

import yaml

from ranger_sdk.auth import AuthProvider, Basic, HadoopSimple, Jwt, Kerberos, NoAuth, TrustedHeader

DEFAULT_PATH = os.path.join(os.path.expanduser("~"), ".config", "rangerctl", "config.yaml")


class ConfigError(Exception):
    pass


def load_config(path: Optional[str] = None) -> Dict[str, Any]:
    path = path or os.environ.get("RANGERCTL_CONFIG") or DEFAULT_PATH
    if not os.path.exists(path):
        return {"profiles": {}}
    with open(path, encoding="utf-8") as fh:
        data = yaml.safe_load(fh) or {}
    data.setdefault("profiles", {})
    return data


def _read_file(path: str) -> str:
    with open(os.path.expanduser(path), encoding="utf-8") as fh:
        return fh.read().strip()


def _secret(cfg: Dict[str, Any], name: str) -> Any:
    """Resolve ``<name>``, ``<name>_env``, ``<name>_file`` or ``<name>_command`` lazily."""
    if f"{name}_env" in cfg:
        var = cfg[f"{name}_env"]
        return lambda: os.environ[var]
    if f"{name}_file" in cfg:
        p = cfg[f"{name}_file"]
        return lambda: _read_file(p)
    if f"{name}_command" in cfg:
        cmd = cfg[f"{name}_command"]
        return lambda: subprocess.run(shlex.split(cmd), check=True, capture_output=True, text=True).stdout.strip()
    if name in cfg:
        return cfg[name]
    raise ConfigError(f"auth needs one of {name}, {name}_env, {name}_file, {name}_command")


def build_auth(cfg: Optional[Dict[str, Any]]) -> AuthProvider:
    if not cfg:
        return NoAuth()
    kind = str(cfg.get("type", "")).lower().replace("-", "_")
    if kind == "basic":
        return Basic(cfg["username"], _secret(cfg, "password"))
    if kind == "kerberos":
        return Kerberos(principal=cfg.get("principal"), keytab=cfg.get("keytab"), krb5_conf=cfg.get("krb5_conf"),
                        service=cfg.get("service", "HTTP"))
    if kind == "jwt":
        return Jwt(_secret(cfg, "token"))
    if kind in ("trusted_header", "header"):
        return TrustedHeader(cfg["user"], cfg.get("header", "X-Forwarded-User"))
    if kind in ("simple", "hadoop_simple"):
        return HadoopSimple(cfg["user"])
    if kind in ("none", ""):
        return NoAuth()
    raise ConfigError(f"unknown auth type {cfg.get('type')!r}")


_CLIENT_KEYS = {"verify", "cert", "connect_timeout", "read_timeout", "cooldown", "headers", "verify_hostname",
                "trust_env"}


def client_options(section: Dict[str, Any]) -> Dict[str, Any]:
    opts = {k: v for k, v in section.items() if k in _CLIENT_KEYS}
    if isinstance(opts.get("verify"), str):
        opts["verify"] = os.path.expanduser(opts["verify"])
    if isinstance(opts.get("cert"), list):
        opts["cert"] = tuple(opts["cert"])
    opts["auth"] = build_auth(section.get("auth"))
    return opts


def section(config: Dict[str, Any], profile: Optional[str], server: str) -> Dict[str, Any]:
    name = profile or os.environ.get("RANGERCTL_PROFILE") or config.get("default_profile")
    profiles = config.get("profiles") or {}
    if name is None:
        if len(profiles) == 1:
            name = next(iter(profiles))
        else:
            raise ConfigError("no profile selected: pass --profile or set default_profile")
    if name not in profiles:
        raise ConfigError(f"profile {name!r} not found in config")
    sec = (profiles[name] or {}).get(server)
    if not sec or not sec.get("urls"):
        raise ConfigError(f"profile {name!r} has no '{server}' section with urls")
    return sec
