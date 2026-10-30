# ranger-sdk (Python)

Typed Python client for Apache Ranger Admin, KMS and PDP (`/authz/v1`), with policy-as-code and the
`rangerctl` CLI. Targets Ranger 2.9.0 and works with 2.5–2.9 servers (features are detected per server).

```bash
pip install ranger-sdk              # core: httpx + pydantic v2
pip install 'ranger-sdk[kerberos]'  # SPNEGO via gssapi
pip install 'ranger-sdk[cli]'       # rangerctl
```

```python
from ranger_sdk import RangerAdmin, RangerPdp
from ranger_sdk.auth import Basic, Jwt
from ranger_sdk.models import Policy

with RangerAdmin(["https://ranger1:6182", "https://ranger2:6182"], auth=Basic("admin", "secret"),
                 verify="/etc/pki/ranger-ca.pem") as admin:
    p = admin.policies.get_by_name("cm_hive", "sales_read")
    admin.policies.apply(p.model_copy(update={"description": "managed"}))

def token() -> str:  # re-read on expiry / 401
    with open("/run/secrets/pdp.jwt") as fh:
        return fh.read().strip()

with RangerPdp("https://pdp:6500", auth=Jwt(token),
               service="dev_hive", service_type="hive") as pdp:
    pdp.is_allowed("alice", resource={"database": "sales", "table": "orders"}, access="select")
```

See the repository README for the full guide (async, policy-as-code, CLI profiles, errors).
