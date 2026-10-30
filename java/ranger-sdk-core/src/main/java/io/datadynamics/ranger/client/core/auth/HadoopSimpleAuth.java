package io.datadynamics.ranger.client.core.auth;

import io.datadynamics.ranger.client.core.ServerKind;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Hadoop simple auth ({@code ?user.name=}), used by Ranger KMS without Kerberos. */
public final class HadoopSimpleAuth implements AuthProvider {
    private final String user;

    private HadoopSimpleAuth(String user) {
        this.user = user;
    }

    public static HadoopSimpleAuth of(String user) {
        return new HadoopSimpleAuth(user);
    }

    @Override
    public Map<String, String> queryParams() {
        return Collections.singletonMap("user.name", user);
    }

    @Override
    public Set<ServerKind> servers() {
        return EnumSet.of(ServerKind.KMS, ServerKind.PDP);
    }

    @Override
    public String describe() {
        return "HadoopSimpleAuth(" + user + ")";
    }
}
