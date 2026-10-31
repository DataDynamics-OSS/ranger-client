#!/usr/bin/env bash
# Smoke test for the demo stack: run after `docker compose up -d --wait` (from ranger-client-demo/).
# Checks the app pages, a PDP decision for a seeded policy, and the seeded KMS key.
# Ports follow ranger-client-demo/.env; override with APP_PORT / PDP_PORT / KMS_PORT.
set -uo pipefail

cd "$(dirname "$0")/.."
# Ports from .env unless already set in the environment (same precedence as docker compose).
for var in APP_PORT PDP_PORT KMS_PORT; do
  [[ -z "${!var:-}" && -f .env ]] && declare "$var=$(grep -E "^$var=" .env | cut -d= -f2)"
done
APP="http://localhost:${APP_PORT:-8080}"
PDP="http://localhost:${PDP_PORT:-6500}"
KMS="http://localhost:${KMS_PORT:-9292}"
TIMEOUT=${SMOKE_TIMEOUT:-300}

fail=0
check() {  # check <name> <command...>
  local name=$1; shift
  if out=$("$@" 2>&1); then
    echo "ok    $name"
  else
    echo "FAIL  $name"; echo "$out" | head -5 | sed 's/^/      /'; fail=1
  fi
}

echo "Waiting for $APP/actuator/health/readiness (up to ${TIMEOUT}s)..."
for ((i = 0; i < TIMEOUT; i += 5)); do
  curl -sf "$APP/actuator/health/readiness" >/dev/null && break
  sleep 5
done

# The seeder runs in the background after startup; wait for it so the data checks below are meaningful.
for ((i = 0; i < 120; i += 5)); do
  page=$(curl -sf "$APP/") && grep -qE '>(DONE|FAILED)<' <<<"$page" && break
  sleep 5
done

check "app health"   curl -sf "$APP/actuator/health"
for path in / /policies /pdp /kms /lab /calls; do
  check "GET $path" curl -sf -o /dev/null "$APP$path"
done
check "seed status DONE" bash -c "curl -sf '$APP/' | grep -q '>DONE<'"

decision=$(curl -sf -X POST "$PDP/authz/v1/authorize" -H 'Content-Type: application/json' -H 'X-Forwarded-User: demo-app' \
  -d '{"requestId":"smoke","user":{"name":"alice"},"access":{"resource":{"name":"table:sales/orders"},"permissions":["select"]},
       "context":{"serviceName":"demo_hive","serviceType":"hive"}}')
check "PDP: alice may select sales.orders" grep -q '"decision":"ALLOW"' <<<"$decision"

check "KMS: demo-key exists" bash -c "curl -sf '$KMS/kms/v1/keys/names?user.name=keyadmin' | grep -q demo-key"

if ((fail)); then echo "Smoke test FAILED"; exit 1; fi
echo "Smoke test passed"
