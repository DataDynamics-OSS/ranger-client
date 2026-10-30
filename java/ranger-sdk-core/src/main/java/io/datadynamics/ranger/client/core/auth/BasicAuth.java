package io.datadynamics.ranger.client.core.auth;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * HTTP Basic. The password is pulled from a supplier on every request so it can come from a vault and
 * never has to be kept as a String.
 */
public final class BasicAuth implements AuthProvider {
    private final String username;
    private final Supplier<char[]> password;
    private final boolean rotating;

    private BasicAuth(String username, Supplier<char[]> password, boolean rotating) {
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.rotating = rotating;
    }

    /** Password supplied on demand (may rotate; a 401 triggers one retry with a fresh value). */
    public static BasicAuth of(String username, Supplier<char[]> password) {
        return new BasicAuth(username, password, true);
    }

    public static BasicAuth of(String username, char[] password) {
        char[] copy = password.clone();
        return new BasicAuth(username, copy::clone, false);
    }

    @Override
    public Map<String, String> headers(URI target) {
        char[] pw = password.get();
        CharBuffer chars = CharBuffer.allocate(username.length() + 1 + pw.length);
        chars.put(username).put(':').put(pw).flip();
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(chars);
        byte[] raw = new byte[bytes.remaining()];
        bytes.get(raw);
        Arrays.fill(pw, '\0');
        Arrays.fill(chars.array(), '\0');
        String value = "Basic " + Base64.getEncoder().encodeToString(raw);
        Arrays.fill(raw, (byte) 0);
        return Collections.singletonMap("Authorization", value);
    }

    @Override
    public boolean refresh() {
        return rotating;
    }

    @Override
    public String describe() {
        return "BasicAuth(" + username + ")";
    }

    @Override
    public String toString() {
        return describe();
    }
}
