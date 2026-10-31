# Ranger KMS 2.9.0 on the official Ranger base image, built from the Apache binary release.
# Adapted from apache/ranger dev-support/ranger-docker/Dockerfile.ranger-kms (Postgres only, no Kerberos).
# Build context: ranger-client-demo/docker
ARG RANGER_VERSION=2.9.0
ARG RANGER_BASE_IMAGE=apache/ranger-base
ARG RANGER_BASE_VERSION=20260918-1-8

# The official Admin image already ships the Postgres JDBC driver Ranger is tested with.
FROM apache/ranger:${RANGER_VERSION} AS admin

FROM ${RANGER_BASE_IMAGE}:${RANGER_BASE_VERSION}
ARG RANGER_VERSION
ARG KMS_SHA512=f23354059cf71a8756d47df48ecadb23881671cfc6f9ff33c962252b251447a6928ec328485898bdebb12ce7a0eda4c81e36c9ebfe1070fe5270422d698805ae

USER root
COPY fetch-release.sh /usr/local/bin/
RUN    fetch-release.sh "${RANGER_VERSION}/services/kms/ranger-${RANGER_VERSION}-kms.tar.gz" "${KMS_SHA512}" /tmp/kms.tar.gz \
    && tar xzf /tmp/kms.tar.gz --directory=${RANGER_HOME} \
    && rm -f /tmp/kms.tar.gz \
    && ln -s ${RANGER_HOME}/ranger-${RANGER_VERSION}-kms ${RANGER_HOME}/kms \
    && rm -f ${RANGER_HOME}/kms/install.properties \
    && mkdir -p /var/run/ranger_kms /var/log/ranger/kms /etc/ranger /usr/share/java \
    && touch /etc/init.d/ranger-kms \
    && ln -s ${RANGER_HOME}/kms/ranger-kms-services.sh /usr/bin/ranger-kms-services.sh
COPY --from=admin /usr/share/java/postgresql.jar /usr/share/java/postgresql.jar

COPY kms/install.properties ${RANGER_SCRIPTS}/kms-install.properties
COPY kms/entrypoint.sh      ${RANGER_SCRIPTS}/ranger-kms.sh
RUN    chown -R rangerkms:ranger ${RANGER_HOME}/kms/ ${RANGER_SCRIPTS}/ /var/run/ranger_kms/ /var/log/ranger/ /etc/ranger \
    && chmod 755 ${RANGER_SCRIPTS}/ranger-kms.sh

EXPOSE 9292
ENTRYPOINT ["/home/ranger/scripts/ranger-kms.sh"]
