package io.datadynamics.ranger.client.core;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * TLS settings: system trust, a JKS/PKCS12 truststore, or a PEM CA bundle (private CAs in closed
 * networks), optionally with a client keystore for mutual TLS.
 */
public final class Tls {
    private static final System.Logger LOG = System.getLogger("io.datadynamics.ranger.client");

    private final KeyStore trustStore;
    private final KeyStore keyStore;
    private final char[] keyPassword;
    private final boolean insecure;

    private Tls(KeyStore trustStore, KeyStore keyStore, char[] keyPassword, boolean insecure) {
        this.trustStore = trustStore;
        this.keyStore = keyStore;
        this.keyPassword = keyPassword;
        this.insecure = insecure;
    }

    /** The JVM's default trust store. */
    public static Tls system() {
        return new Tls(null, null, null, false);
    }

    /** A JKS or PKCS12 trust store (type detected from the content). */
    public static Tls truststore(Path path, char[] password) {
        return new Tls(load(path, password), null, null, false);
    }

    /** One or more PEM-encoded CA certificates. */
    public static Tls pem(Path caBundle) {
        try (InputStream in = Files.newInputStream(caBundle)) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) {
                throw new IllegalArgumentException("no certificate in " + caBundle);
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : certs) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            return new Tls(ks, null, null, false);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("cannot read CA bundle " + caBundle + ": " + e.getMessage(), e);
        }
    }

    /**
     * Trust every certificate. Testing only: logs a warning. Hostname verification of the JDK HTTP
     * client is JVM wide; see {@link #disableHostnameVerificationGlobally()}.
     */
    public static Tls insecure() {
        LOG.log(Level.WARNING, "TLS certificate verification is disabled; use only for testing");
        return new Tls(null, null, null, true);
    }

    /**
     * The JDK HTTP client only offers a JVM-wide switch for hostname verification. This sets it (for
     * every HttpClient in the process) and logs a warning; call it explicitly if you really need it.
     */
    public static void disableHostnameVerificationGlobally() {
        LOG.log(Level.WARNING, "TLS hostname verification disabled JVM-wide; use only for testing");
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    /** Add a client certificate (mutual TLS) from a JKS/PKCS12 key store. */
    public Tls withKeystore(Path path, char[] password) {
        return new Tls(trustStore, load(path, password), password.clone(), insecure);
    }

    public boolean isInsecure() {
        return insecure;
    }

    public SSLContext sslContext() {
        try {
            if (trustStore == null && keyStore == null && !insecure) {
                return SSLContext.getDefault();
            }
            TrustManager[] tms;
            if (insecure) {
                tms = new TrustManager[] {new TrustAll()};
            } else if (trustStore != null) {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                tms = tmf.getTrustManagers();
            } else {
                tms = null;
            }
            KeyManagerFactory kmf = null;
            if (keyStore != null) {
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, keyPassword);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tms, new SecureRandom());
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build TLS context: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(Path path, char[] password) {
        GeneralSecurityException last = null;
        for (String type : new String[] {"PKCS12", "JKS"}) {
            try (InputStream in = Files.newInputStream(path)) {
                KeyStore ks = KeyStore.getInstance(type);
                ks.load(in, password);
                return ks;
            } catch (GeneralSecurityException e) {
                last = e;
            } catch (IOException e) {
                if (!(e.getCause() instanceof GeneralSecurityException) && !"JKS".equals(type)) {
                    continue;
                }
                last = new GeneralSecurityException(e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("cannot read key store " + path + (last == null ? "" : ": " + last.getMessage()), last);
    }

    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
