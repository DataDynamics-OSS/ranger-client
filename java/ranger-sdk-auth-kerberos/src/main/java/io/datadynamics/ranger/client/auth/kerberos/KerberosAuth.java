package io.datadynamics.ranger.client.auth.kerberos;

import io.datadynamics.ranger.client.core.auth.AuthProvider;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.kerberos.KerberosTicket;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.file.Path;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SPNEGO ({@code Authorization: Negotiate}) with a JAAS login done by the SDK itself: no jaas.conf, no
 * kinit and no reliance on the system keytab. The TGT is renewed by logging in again shortly before it
 * expires (and after a 401). Combined with Ranger Admin's session cookie, a Negotiate token is only sent
 * when the client has no session.
 *
 * <pre>{@code
 * KerberosAuth.keytab("svc-argus@CORP.LOCAL", Path.of("/etc/security/argus.keytab"))
 *             .krb5Conf(Path.of("/etc/argus/krb5.conf"));
 * }</pre>
 */
public final class KerberosAuth implements AuthProvider {
    private static final System.Logger LOG = System.getLogger("io.datadynamics.ranger.client");
    private static final Oid SPNEGO = oid("1.3.6.1.5.5.2");

    private final String principal;
    private final Path keytab;
    private final Path ticketCache;
    private final String service;
    private final String hostOverride;
    private final Duration renewBefore;
    private Subject subject;
    private LoginContext login;

    private KerberosAuth(String principal, Path keytab, Path ticketCache, String service, String hostOverride, Duration renewBefore) {
        this.principal = principal;
        this.keytab = keytab;
        this.ticketCache = ticketCache;
        this.service = service;
        this.hostOverride = hostOverride;
        this.renewBefore = renewBefore;
    }

    /** Log in from a keytab. */
    public static KerberosAuth keytab(String principal, Path keytab) {
        return new KerberosAuth(Objects.requireNonNull(principal, "principal"), Objects.requireNonNull(keytab, "keytab"),
                null, "HTTP", null, Duration.ofMinutes(5));
    }

    /** Use an existing ticket cache (after kinit); {@code cache} may be null for the default one. */
    public static KerberosAuth ticketCache(String principal, Path cache) {
        return new KerberosAuth(principal, null, cache, "HTTP", null, Duration.ofMinutes(5));
    }

    /**
     * Use this krb5.conf instead of the system one. The JDK reads it from a JVM-wide property, so this
     * applies to the whole process.
     */
    public KerberosAuth krb5Conf(Path krb5Conf) {
        System.setProperty("java.security.krb5.conf", krb5Conf.toString());
        System.setProperty("sun.security.krb5.disableReferrals", System.getProperty("sun.security.krb5.disableReferrals", "false"));
        return this;
    }

    /** Service part of the server principal (default {@code HTTP}). */
    public KerberosAuth service(String service) {
        return new KerberosAuth(principal, keytab, ticketCache, service, hostOverride, renewBefore);
    }

    /** Use this host name in the server principal instead of the URL's (e.g. behind a load balancer). */
    public KerberosAuth hostname(String host) {
        return new KerberosAuth(principal, keytab, ticketCache, service, host, renewBefore);
    }

    public KerberosAuth renewBefore(Duration d) {
        return new KerberosAuth(principal, keytab, ticketCache, service, hostOverride, d);
    }

    @Override
    public Map<String, String> headers(URI target) {
        String host = hostOverride != null ? hostOverride : target.getHost();
        Subject s = currentSubject();
        try {
            byte[] token = Subject.doAs(s, (PrivilegedExceptionAction<byte[]>) () -> {
                GSSManager manager = GSSManager.getInstance();
                GSSName server = manager.createName(service + "@" + host, GSSName.NT_HOSTBASED_SERVICE);
                GSSCredential creds = manager.createCredential(null, GSSCredential.DEFAULT_LIFETIME, SPNEGO, GSSCredential.INITIATE_ONLY);
                GSSContext ctx = manager.createContext(server.canonicalize(SPNEGO), SPNEGO, creds, GSSContext.DEFAULT_LIFETIME);
                try {
                    ctx.requestMutualAuth(false);
                    ctx.requestCredDeleg(false);
                    return ctx.initSecContext(new byte[0], 0, 0);
                } finally {
                    ctx.dispose();
                }
            });
            return Collections.singletonMap("Authorization", "Negotiate " + Base64.getEncoder().encodeToString(token));
        } catch (PrivilegedActionException e) {
            throw new IllegalStateException("SPNEGO token for " + service + "@" + host + " failed: " + e.getException(), e.getException());
        }
    }

    @Override
    public boolean refresh() {
        synchronized (this) {
            logout();
        }
        return true;
    }

    @Override
    public boolean supportsSessionReuse() {
        return true;
    }

    @Override
    public String describe() {
        return "KerberosAuth(" + principal + (keytab != null ? ", keytab=***" : ", ticketCache") + ")";
    }

    @Override
    public String toString() {
        return describe();
    }

    private synchronized Subject currentSubject() {
        if (subject == null || expiresSoon(subject)) {
            logout();
            try {
                LoginContext lc = new LoginContext("ranger-sdk", null, null, jaasConfig());
                lc.login();
                login = lc;
                subject = lc.getSubject();
                LOG.log(Level.DEBUG, "Kerberos login as {0}", principal);
            } catch (LoginException e) {
                throw new IllegalStateException("Kerberos login failed for " + principal + ": " + e.getMessage(), e);
            }
        }
        return subject;
    }

    private boolean expiresSoon(Subject s) {
        for (KerberosTicket t : s.getPrivateCredentials(KerberosTicket.class)) {
            KerberosPrincipal server = t.getServer();
            if (server != null && server.getName().startsWith("krbtgt/")) {
                return t.getEndTime() == null || Instant.now().plus(renewBefore).isAfter(t.getEndTime().toInstant());
            }
        }
        return false;
    }

    private void logout() {
        if (login != null) {
            try {
                login.logout();
            } catch (LoginException ignored) {
                // the subject is dropped anyway
            }
        }
        login = null;
        subject = null;
    }

    Configuration jaasConfig() {
        Map<String, String> opts = new HashMap<>();
        opts.put("doNotPrompt", "true");
        opts.put("refreshKrb5Config", "true");
        opts.put("isInitiator", "true");
        if (principal != null) {
            opts.put("principal", principal);
        }
        if (keytab != null) {
            opts.put("useKeyTab", "true");
            opts.put("keyTab", keytab.toString());
            opts.put("storeKey", "true");
        } else {
            opts.put("useTicketCache", "true");
            opts.put("renewTGT", "true");
            if (ticketCache != null) {
                opts.put("ticketCache", ticketCache.toString());
            }
        }
        AppConfigurationEntry entry = new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, opts);
        return new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                return new AppConfigurationEntry[] {entry};
            }
        };
    }

    private static Oid oid(String s) {
        try {
            return new Oid(s);
        } catch (GSSException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
