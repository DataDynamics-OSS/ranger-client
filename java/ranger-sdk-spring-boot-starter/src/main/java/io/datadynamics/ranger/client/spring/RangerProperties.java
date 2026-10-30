package io.datadynamics.ranger.client.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code ranger.*} settings. A client bean is created for each of {@code admin}, {@code pdp} and {@code kms}
 * that has {@code urls}. Unset values keep the SDK defaults.
 */
@ConfigurationProperties(prefix = "ranger")
public class RangerProperties {
    /** Ranger Admin (policies, services, roles, zones, users, tags). */
    private Server admin = new Server();
    /** Ranger PDP server ({@code /authz/v1}, Ranger 2.9+). */
    private Pdp pdp = new Pdp();
    /** Ranger KMS. */
    private Server kms = new Server();
    private Metrics metrics = new Metrics();
    private Tracing tracing = new Tracing();

    public Server getAdmin() {
        return admin;
    }

    public void setAdmin(Server admin) {
        this.admin = admin;
    }

    public Pdp getPdp() {
        return pdp;
    }

    public void setPdp(Pdp pdp) {
        this.pdp = pdp;
    }

    public Server getKms() {
        return kms;
    }

    public void setKms(Server kms) {
        this.kms = kms;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public Tracing getTracing() {
        return tracing;
    }

    public void setTracing(Tracing tracing) {
        this.tracing = tracing;
    }

    /** Connection settings shared by all three servers. */
    public static class Server {
        /** Server URLs; requests fail over between them. */
        private List<String> urls = new ArrayList<>();
        /** Declared server version (e.g. 2.9.0): features are then decided without probing the server. */
        private String serverVersion;
        private Auth auth = new Auth();
        private Tls tls = new Tls();
        private Retry retry = new Retry();
        /** Connect timeout (SDK default 5s). */
        private Duration connectTimeout;
        /** Read timeout (SDK default 30s). */
        private Duration readTimeout;
        /** How long a failed node is skipped (SDK default 30s). */
        private Duration cooldown;
        /** Extra headers sent with every request. */
        private Map<String, String> headers = new LinkedHashMap<>();

        public List<String> getUrls() {
            return urls;
        }

        public void setUrls(List<String> urls) {
            this.urls = urls;
        }

        public String getServerVersion() {
            return serverVersion;
        }

        public void setServerVersion(String serverVersion) {
            this.serverVersion = serverVersion;
        }

        public Auth getAuth() {
            return auth;
        }

        public void setAuth(Auth auth) {
            this.auth = auth;
        }

        public Tls getTls() {
            return tls;
        }

        public void setTls(Tls tls) {
            this.tls = tls;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
    }

    /** PDP adds the default service used by the convenience calls and the decision cache. */
    public static class Pdp extends Server {
        /** Ranger service name used when a call does not name one (e.g. dev_hive). */
        private String service;
        /** Service type of {@link #service} (e.g. hive). */
        private String serviceType;
        private Cache cache = new Cache();

        public String getService() {
            return service;
        }

        public void setService(String service) {
            this.service = service;
        }

        public String getServiceType() {
            return serviceType;
        }

        public void setServiceType(String serviceType) {
            this.serviceType = serviceType;
        }

        public Cache getCache() {
            return cache;
        }

        public void setCache(Cache cache) {
            this.cache = cache;
        }
    }

    /** Client-side decision cache; off unless {@code ttl} is set (policy changes then show up late). */
    public static class Cache {
        private Duration ttl;
        private int maxEntries = 10_000;

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    public enum AuthType { NONE, BASIC, JWT, KERBEROS, HEADER, HADOOP_SIMPLE }

    /**
     * Authentication. Prefer {@code password-file} / {@code token-file} (re-read on refresh) or
     * {@code ${ENV}} placeholders over literal secrets.
     */
    public static class Auth {
        private AuthType type = AuthType.NONE;
        /** basic: user name. */
        private String username;
        /** basic: password. */
        private String password;
        /** basic: file holding the password. */
        private Path passwordFile;
        /** jwt: token. */
        private String token;
        /** jwt: file holding the token, re-read when the token expires or is rejected. */
        private Path tokenFile;
        /** kerberos: client principal. */
        private String principal;
        /** kerberos: keytab (otherwise {@code ticket-cache} is used). */
        private Path keytab;
        /** kerberos: ticket cache, when no keytab is given. */
        private Path ticketCache;
        /** kerberos: krb5.conf (instead of the system default). */
        private Path krb5Conf;
        /** kerberos: service part of the server principal (default HTTP). */
        private String spnegoService;
        /** kerberos: host part of the server principal, when it differs from the URL host. */
        private String spnegoHostname;
        /** header / hadoop-simple: user to act as. */
        private String user;
        /** header: header name (default X-Forwarded-User). */
        private String header;

        public AuthType getType() {
            return type;
        }

        public void setType(AuthType type) {
            this.type = type;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Path getPasswordFile() {
            return passwordFile;
        }

        public void setPasswordFile(Path passwordFile) {
            this.passwordFile = passwordFile;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public Path getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(Path tokenFile) {
            this.tokenFile = tokenFile;
        }

        public String getPrincipal() {
            return principal;
        }

        public void setPrincipal(String principal) {
            this.principal = principal;
        }

        public Path getKeytab() {
            return keytab;
        }

        public void setKeytab(Path keytab) {
            this.keytab = keytab;
        }

        public Path getTicketCache() {
            return ticketCache;
        }

        public void setTicketCache(Path ticketCache) {
            this.ticketCache = ticketCache;
        }

        public Path getKrb5Conf() {
            return krb5Conf;
        }

        public void setKrb5Conf(Path krb5Conf) {
            this.krb5Conf = krb5Conf;
        }

        public String getSpnegoService() {
            return spnegoService;
        }

        public void setSpnegoService(String spnegoService) {
            this.spnegoService = spnegoService;
        }

        public String getSpnegoHostname() {
            return spnegoHostname;
        }

        public void setSpnegoHostname(String spnegoHostname) {
            this.spnegoHostname = spnegoHostname;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }
    }

    /** TLS trust and client certificates. Without settings the JVM trust store is used. */
    public static class Tls {
        /** PEM bundle of trusted CAs. */
        private Path ca;
        /** JKS/PKCS12 trust store (alternative to {@code ca}). */
        private Path truststore;
        private String truststorePassword;
        /** Client certificate key store (mutual TLS). */
        private Path keystore;
        private String keystorePassword;
        /** Trust every certificate. Testing only; logged as a warning. */
        private boolean insecure;

        public Path getCa() {
            return ca;
        }

        public void setCa(Path ca) {
            this.ca = ca;
        }

        public Path getTruststore() {
            return truststore;
        }

        public void setTruststore(Path truststore) {
            this.truststore = truststore;
        }

        public String getTruststorePassword() {
            return truststorePassword;
        }

        public void setTruststorePassword(String truststorePassword) {
            this.truststorePassword = truststorePassword;
        }

        public Path getKeystore() {
            return keystore;
        }

        public void setKeystore(Path keystore) {
            this.keystore = keystore;
        }

        public String getKeystorePassword() {
            return keystorePassword;
        }

        public void setKeystorePassword(String keystorePassword) {
            this.keystorePassword = keystorePassword;
        }

        public boolean isInsecure() {
            return insecure;
        }

        public void setInsecure(boolean insecure) {
            this.insecure = insecure;
        }
    }

    /** Retry of idempotent calls (SDK default: 3 attempts, exponential backoff with jitter). */
    public static class Retry {
        private Integer maxAttempts;
        private Duration baseDelay;
        private Duration maxDelay;
        /** Also retry POSTs (creates). Off: a retried create can produce duplicates. */
        private boolean nonIdempotent;

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBaseDelay() {
            return baseDelay;
        }

        public void setBaseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public boolean isNonIdempotent() {
            return nonIdempotent;
        }

        public void setNonIdempotent(boolean nonIdempotent) {
            this.nonIdempotent = nonIdempotent;
        }
    }

    /** Micrometer metrics of SDK requests (needs a MeterRegistry). */
    public static class Metrics {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * OpenTelemetry spans for SDK requests and trace-context propagation (needs ranger-sdk-opentelemetry and
     * an OpenTelemetry bean, e.g. from spring-boot-starter-opentelemetry).
     */
    public static class Tracing {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
