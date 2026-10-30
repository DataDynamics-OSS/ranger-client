package io.datadynamics.ranger.client.kms;

import io.datadynamics.ranger.client.core.AbstractClientBuilder;
import io.datadynamics.ranger.client.core.AbstractRangerClient;
import io.datadynamics.ranger.client.core.ServerKind;
import io.datadynamics.ranger.client.core.http.HttpCall;
import io.datadynamics.ranger.client.core.http.Transport;
import io.datadynamics.ranger.client.model.EncryptedKeyVersion;
import io.datadynamics.ranger.client.model.KeyMetadata;
import io.datadynamics.ranger.client.model.KeyVersion;
import io.datadynamics.ranger.client.model.RangerJson;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ranger KMS client (Hadoop KMS REST API, {@code /kms/v1}). */
public final class RangerKmsClient extends AbstractRangerClient<RangerKmsClient> {
    private static final String V1 = "/kms/v1";

    private RangerKmsClient(Transport transport) {
        super(transport);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String seg(String s) {
        return Transport.seg(s);
    }

    public KeyVersion createKey(String name, String cipher, int length, String description, Map<String, String> attributes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("cipher", cipher == null ? "AES/CTR/NoPadding" : cipher);
        body.put("length", length);
        if (description != null) {
            body.put("description", description);
        }
        if (attributes != null) {
            body.put("attributes", attributes);
        }
        return transport.send(HttpCall.post(V1 + "/keys").json(body)).as(KeyVersion.class);
    }

    public KeyVersion createKey(String name) {
        return createKey(name, null, 128, null, null);
    }

    public KeyVersion rolloverKey(String name) {
        return transport.send(HttpCall.post(V1 + "/key/" + seg(name)).json(Map.of())).as(KeyVersion.class);
    }

    public void deleteKey(String name) {
        transport.send(HttpCall.delete(V1 + "/key/" + seg(name)));
    }

    public void invalidateCache(String name) {
        transport.send(HttpCall.post(V1 + "/key/" + seg(name) + "/_invalidatecache").idempotent(true));
    }

    public KeyMetadata metadata(String name) {
        return transport.send(HttpCall.get(V1 + "/key/" + seg(name) + "/_metadata")).as(KeyMetadata.class);
    }

    public List<KeyMetadata> metadata(Collection<String> names) {
        return transport.send(HttpCall.get(V1 + "/keys/metadata").query("key", names)).as(new TypeReference<List<KeyMetadata>>() {
        });
    }

    public KeyVersion currentVersion(String name) {
        return transport.send(HttpCall.get(V1 + "/key/" + seg(name) + "/_currentversion")).as(KeyVersion.class);
    }

    public List<KeyVersion> versions(String name) {
        return transport.send(HttpCall.get(V1 + "/key/" + seg(name) + "/_versions")).as(new TypeReference<List<KeyVersion>>() {
        });
    }

    public KeyVersion keyVersion(String versionName) {
        return transport.send(HttpCall.get(V1 + "/keyversion/" + seg(versionName))).as(KeyVersion.class);
    }

    public List<String> names() {
        return transport.send(HttpCall.get(V1 + "/keys/names")).as(new TypeReference<List<String>>() {
        });
    }

    public List<EncryptedKeyVersion> generateEek(String name, int numKeys) {
        return transport.send(HttpCall.get(V1 + "/key/" + seg(name) + "/_eek").query("eek_op", "generate").query("num_keys", numKeys))
                .as(new TypeReference<List<EncryptedKeyVersion>>() {
                });
    }

    public KeyVersion decryptEek(String name, EncryptedKeyVersion eek) {
        return transport.send(HttpCall.post(V1 + "/keyversion/" + seg(eek.getVersionName()) + "/_eek")
                .query("eek_op", "decrypt").json(eekBody(name, eek)).idempotent(true)).as(KeyVersion.class);
    }

    public EncryptedKeyVersion reencryptEek(String name, EncryptedKeyVersion eek) {
        return transport.send(HttpCall.post(V1 + "/keyversion/" + seg(eek.getVersionName()) + "/_eek")
                .query("eek_op", "reencrypt").json(eekBody(name, eek)).idempotent(true)).as(EncryptedKeyVersion.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> status() {
        return RangerJson.read(transport.send(HttpCall.get("/kms/api/status")).body(), Map.class);
    }

    private static Map<String, Object> eekBody(String name, EncryptedKeyVersion eek) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("iv", eek.getIv());
        body.put("material", eek.getEncryptedKeyVersion() == null ? null : eek.getEncryptedKeyVersion().getMaterial());
        return body;
    }

    /** Builder; see {@link AbstractClientBuilder} for the shared options. */
    public static final class Builder extends AbstractClientBuilder<RangerKmsClient, Builder> {
        Builder() {
            super(ServerKind.KMS);
        }

        @Override
        public RangerKmsClient build() {
            return new RangerKmsClient(transport());
        }
    }
}
