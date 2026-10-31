#!/bin/bash
# Ranger PDP entrypoint: writes ranger-pdp-site.xml with the Admin password, then runs PDP in the foreground.
set -eu

: "${RANGER_ADMIN_PASSWORD:?RANGER_ADMIN_PASSWORD is required}"

# XML-escape, then escape for the sed replacement below.
escaped=$(printf '%s' "$RANGER_ADMIN_PASSWORD" \
  | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
  | sed -e 's/[&|\\]/\\&/g')
sed "s|@RANGER_ADMIN_PASSWORD@|${escaped}|" "${RANGER_SCRIPTS}/ranger-pdp-site.xml" > "${RANGER_HOME}/pdp/conf/ranger-pdp-site.xml"

cd "${RANGER_HOME}/pdp"
exec ./ranger-pdp-services.sh run
