package io.datadynamics.ranger.demo.trace;

import io.datadynamics.ranger.client.core.RequestInterceptor;
import io.datadynamics.ranger.client.core.ServerKind;
import io.datadynamics.ranger.client.core.http.Masking;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * 화면이 실행하는 SDK 호출을 대신 실행하고, 그 호출이 한 일을 기록합니다.
 *
 * <pre>{@code
 * SdkCall call = calls.run("admin.policies().getByName(\"demo_hive\", \"sales_read\")",
 *         () -> admin.policies().getByName("demo_hive", "sales_read"));
 * }</pre>
 *
 * 코드 문자열은 호출 옆에 손으로 적어 두므로, 화면에는 개발자가 실제로 쓸 코드가 그대로 보입니다.
 * HTTP 시도는 모든 데모 클라이언트에 등록한 {@link #interceptor(String)}가 모읍니다. SDK는 인터셉터를 요청
 * 스레드에서 부르므로 {@code run} 안에서 일어난 시도는 그 호출의 것입니다. 같은 이유로 웹 요청 하나를 처리하는
 * 동안 보낸 시도는 {@link #beginPage()}의 목록에 모이고({@link TraceInterceptor}), 헬스 체크와 시더가 보낸 것까지
 * 모든 시도는 크기가 제한된 전체 이력(요청 기록 화면)에 쌓입니다.
 */
@Component
public class SdkCalls {
    private static final int MAX_CALLS = 100;
    private static final int MAX_ATTEMPTS = 300;
    /** 요청·응답 본문을 보관할 최대 글자 수입니다. 넘는 부분은 잘라 냅니다. */
    static final int MAX_BODY_LENGTH = 20_000;

    private final ThreadLocal<List<HttpAttempt>> current = new ThreadLocal<>();
    private final Deque<SdkCall> recentCalls = new ArrayDeque<>();
    private final Deque<HttpAttempt> recentAttempts = new ArrayDeque<>();
    private final ThreadLocal<List<HttpAttempt>> page = new ThreadLocal<>();
    private final AtomicLong sequence = new AtomicLong();

    /** 현재 스레드(웹 요청)가 보내는 시도를 모으기 시작합니다. 돌려준 목록에는 이후 시도가 계속 추가됩니다. */
    public List<HttpAttempt> beginPage() {
        List<HttpAttempt> attempts = new ArrayList<>();
        page.set(attempts);
        return attempts;
    }

    /** 현재 스레드의 수집을 끝냅니다. 스레드 풀에서 재사용될 스레드에 목록이 남지 않도록 반드시 호출해야 합니다. */
    public void endPage() {
        page.remove();
    }

    public SdkCall run(String code, Callable<?> call) {
        List<HttpAttempt> attempts = new ArrayList<>();
        List<HttpAttempt> outer = current.get();
        current.set(attempts);
        Instant at = Instant.now();
        long start = System.nanoTime();
        Object result = null;
        Throwable error = null;
        try {
            result = call.call();
        } catch (Exception e) {
            error = e;
        } finally {
            current.set(outer);
            if (outer != null) {
                outer.addAll(attempts);
            }
        }
        SdkCall done = new SdkCall(at, code, result, error, List.copyOf(attempts), (System.nanoTime() - start) / 1_000_000);
        synchronized (recentCalls) {
            recentCalls.addFirst(done);
            while (recentCalls.size() > MAX_CALLS) {
                recentCalls.removeLast();
            }
        }
        return done;
    }

    /** For calls without a result, e.g. {@code delete}. */
    public SdkCall runVoid(String code, ThrowingRunnable call) {
        return run(code, () -> {
            call.run();
            return null;
        });
    }

    public List<SdkCall> recentCalls() {
        synchronized (recentCalls) {
            return List.copyOf(recentCalls);
        }
    }

    public List<HttpAttempt> recentAttempts() {
        synchronized (recentAttempts) {
            return List.copyOf(recentAttempts);
        }
    }

    public void clear() {
        synchronized (recentCalls) {
            recentCalls.clear();
        }
        synchronized (recentAttempts) {
            recentAttempts.clear();
        }
    }

    /** 클라이언트 빌더에 추가할 인터셉터입니다. {@code client}는 요청 기록에 보일 클라이언트 이름입니다. */
    public RequestInterceptor interceptor(String client) {
        return (ServerKind server, String method, URI uri, int attempt, String requestId, BiConsumer<String, String> header) -> {
            Instant at = Instant.now();
            long start = System.nanoTime();
            List<HttpAttempt> sink = current.get();
            List<HttpAttempt> pageSink = page.get();
            return new RequestInterceptor.Attempt() {
                private String requestBody;

                @Override
                public void onRequestBody(String contentType, byte[] body) {
                    requestBody = truncate(Masking.body(new String(body, StandardCharsets.UTF_8)));
                }

                @Override
                public void onResponse(int status, byte[] body) {
                    record(status, null, body == null || body.length == 0 ? null
                            : truncate(new String(body, StandardCharsets.UTF_8)));
                }

                @Override
                public void onError(Throwable error) {
                    record(null, error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage()), null);
                }

                private void record(Integer status, String error, String responseBody) {
                    HttpAttempt a = new HttpAttempt(sequence.incrementAndGet(), at, client, server.name().toLowerCase(),
                            method, uri, attempt, requestId, status, error, (System.nanoTime() - start) / 1_000_000,
                            requestBody, responseBody);
                    if (sink != null) {
                        sink.add(a);
                    }
                    if (pageSink != null) {
                        pageSink.add(a);
                    }
                    synchronized (recentAttempts) {
                        recentAttempts.addFirst(a);
                        while (recentAttempts.size() > MAX_ATTEMPTS) {
                            recentAttempts.removeLast();
                        }
                    }
                }
            };
        };
    }

    /** 본문이 너무 길면 앞부분만 남기고, 잘린 글자 수를 끝에 덧붙입니다. 화면과 메모리 사용량을 제한하기 위해서입니다. */
    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_BODY_LENGTH) + "\n... (" + (body.length() - MAX_BODY_LENGTH) + "자 더 있음)";
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
