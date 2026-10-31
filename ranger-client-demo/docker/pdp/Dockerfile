# Ranger PDP 2.9.0 on the official Ranger base image, built from the Apache binary release.
# Adapted from apache/ranger dev-support/ranger-docker/Dockerfile.ranger-pdp (no Kerberos).
# Build context: ranger-client-demo/docker
ARG RANGER_BASE_IMAGE=apache/ranger-base
ARG RANGER_BASE_VERSION=20260918-1-8
FROM ${RANGER_BASE_IMAGE}:${RANGER_BASE_VERSION}

ARG RANGER_VERSION=2.9.0
ARG PDP_SHA512=56228cd9c7b62722fc7a7cafafaff7e3cc485a77c3f50926c5750eb8a6d3d29a13a728555710a645ab603281b95fa845fdbb1b1b5687352a266fe7c749b6d9d6

USER root
COPY fetch-release.sh /usr/local/bin/
RUN    fetch-release.sh "${RANGER_VERSION}/services/pdp/ranger-${RANGER_VERSION}-pdp.tar.gz" "${PDP_SHA512}" /tmp/pdp.tar.gz \
    && tar xzf /tmp/pdp.tar.gz --directory=${RANGER_HOME} \
    && rm -f /tmp/pdp.tar.gz \
    && ln -s ${RANGER_HOME}/ranger-${RANGER_VERSION}-pdp ${RANGER_HOME}/pdp \
    && cp ${RANGER_HOME}/pdp/conf.dist/logback.xml ${RANGER_HOME}/pdp/conf/ \
    && mkdir -p /var/log/ranger/pdp /var/run/ranger /var/ranger/cache/pdp

COPY pdp/ranger-pdp-site.xml ${RANGER_SCRIPTS}/ranger-pdp-site.xml
COPY pdp/entrypoint.sh       ${RANGER_SCRIPTS}/ranger-pdp.sh
RUN    chown -R ranger:ranger ${RANGER_HOME}/ranger-${RANGER_VERSION}-pdp ${RANGER_SCRIPTS}/ /var/log/ranger/ /var/run/ranger /var/ranger \
    && chmod 755 ${RANGER_SCRIPTS}/ranger-pdp.sh

USER ranger
EXPOSE 6500
ENTRYPOINT ["/home/ranger/scripts/ranger-pdp.sh"]
