package io.datadynamics.ranger.client.kms;

import io.datadynamics.ranger.client.core.RangerNotFoundException;
import io.datadynamics.ranger.client.core.auth.HadoopSimpleAuth;
import io.datadynamics.ranger.client.model.EncryptedKeyVersion;
import io.datadynamics.ranger.client.model.KeyVersion;
import io.datadynamics.ranger.client.testing.MockServer;
import io.datadynamics.ranger.client.testing.MockServer.Reply;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangerKmsClientTest {
    @Test
    void eekRoundTrip() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(200, "[{\"versionName\":\"k@0\",\"iv\":\"aXY=\",\"encryptedKeyVersion\":"
                    + "{\"versionName\":\"EEK\",\"material\":\"ZW5j\"}}]"))
             .enqueue(Reply.of(200, "{\"name\":\"k\",\"versionName\":\"EK\",\"material\":\"c2VjcmV0\"}"));
            RangerKmsClient kms = RangerKmsClient.builder().urls(s.url()).auth(HadoopSimpleAuth.of("hdfs")).build();
            List<EncryptedKeyVersion> eeks = kms.generateEek("k", 1);
            assertEquals("/kms/v1/key/k/_eek", s.last().rawPath);
            assertEquals("generate", s.last().query.get("eek_op"));
            assertEquals("hdfs", s.last().query.get("user.name"));

            KeyVersion dek = kms.decryptEek("k", eeks.get(0));
            assertEquals("/kms/v1/keyversion/k%400/_eek", s.last().rawPath);
            assertEquals("decrypt", s.last().query.get("eek_op"));
            assertTrue(s.last().bodyText().contains("\"material\":\"ZW5j\""), s.last().bodyText());
            assertEquals("c2VjcmV0", dek.getMaterial());
            assertFalse(dek.toString().contains("c2VjcmV0"));
        }
    }

    @Test
    void hadoopRemoteExceptionMessage() {
        try (MockServer s = new MockServer()) {
            s.enqueue(Reply.of(404, "{\"RemoteException\":{\"message\":\"Key nope does not exist\",\"exception\":\"x\"}}"));
            RangerKmsClient kms = RangerKmsClient.builder().urls(s.url()).build();
            RangerNotFoundException e = assertThrows(RangerNotFoundException.class, () -> kms.metadata("nope"));
            assertEquals("Key nope does not exist", e.getServerMessage());
        }
    }
}
