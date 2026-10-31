#!/bin/bash
# Ranger KMS entrypoint: first start runs setup.sh (creates the rangerkms DB and master key), then runs KMS in the foreground.
# Adapted from apache/ranger dev-support/ranger-docker/scripts/kms/ranger-kms.sh (Kerberos removed).
set -u

: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
: "${RANGER_KMS_DB_PASSWORD:?RANGER_KMS_DB_PASSWORD is required}"
: "${KMS_MASTER_KEY_PASSWORD:?KMS_MASTER_KEY_PASSWORD is required}"
: "${RANGER_KMS_PLUGIN_USER:?RANGER_KMS_PLUGIN_USER is required}"
: "${RANGER_KMS_PLUGIN_PASSWORD:?RANGER_KMS_PLUGIN_PASSWORD is required}"

if [ ! -e "${RANGER_HOME}/.setupDone" ]; then
  props="${RANGER_HOME}/kms/install.properties"
  cp "${RANGER_SCRIPTS}/kms-install.properties" "$props"
  esc() { printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }
  sed -i \
    -e "s|^db_root_password=.*|db_root_password=$(esc "$POSTGRES_PASSWORD")|" \
    -e "s|^db_password=.*|db_password=$(esc "$RANGER_KMS_DB_PASSWORD")|" \
    -e "s|^KMS_MASTER_KEY_PASSWD=.*|KMS_MASTER_KEY_PASSWD=$(esc "$KMS_MASTER_KEY_PASSWORD")|" \
    "$props"

  cd "${RANGER_HOME}/kms" || exit 1
  if ./setup.sh; then
    rm -f "$props"
    # Ranger Admin 2.9 rejects unauthenticated policy downloads, so give the KMS plugin a basic-auth login.
    security="${RANGER_HOME}/kms/ews/webapp/WEB-INF/classes/conf/ranger-kms-security.xml"
    for kv in "username=${RANGER_KMS_PLUGIN_USER}" "password=${RANGER_KMS_PLUGIN_PASSWORD}"; do
      xmlstarlet ed -L -s /configuration -t elem -n property \
        -s '/configuration/property[last()]' -t elem -n name -v "ranger.plugin.kms.policy.rest.client.${kv%%=*}" \
        -s '/configuration/property[last()]' -t elem -n value -v "${kv#*=}" "$security"
    done
    touch "${RANGER_HOME}/.setupDone"
  else
    echo "Ranger KMS setup.sh failed" >&2
    exit 1
  fi
fi

rm -f /var/run/ranger_kms/rangerkms.pid
cd "${RANGER_HOME}/kms" && ./ranger-kms-services.sh start

sleep 5
pid=$(pgrep -f Dproc_rangerkms | head -1)
if [ -z "$pid" ]; then
  echo "Ranger KMS process exited; see /var/log/ranger/kms" >&2
  tail -n 200 /var/log/ranger/kms/*.log 2>/dev/null
  exit 1
fi
tail -F /var/log/ranger/kms/ranger-kms-*.log --pid="$pid" 2>/dev/null &
while kill -0 "$pid" 2>/dev/null; do sleep 5; done
echo "Ranger KMS process exited" >&2
exit 1
