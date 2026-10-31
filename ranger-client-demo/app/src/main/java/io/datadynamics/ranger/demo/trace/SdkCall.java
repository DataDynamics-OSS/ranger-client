package io.datadynamics.ranger.demo.trace;

import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.demo.web.support.Failure;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of one SDK call made by the demo: the Java code that ran, every HTTP attempt it produced,
 * and either its result or the exception it threw.
 */
public record SdkCall(Instant at, String code, Object result, Throwable error, List<HttpAttempt> attempts, long elapsedMs) {

    public boolean ok() {
        return error == null;
    }

    public String errorType() {
        return error == null ? null : error.getClass().getSimpleName();
    }

    /** Fully qualified exception class (templates may not call methods on java.* objects). */
    public String errorClass() {
        return error == null ? null : error.getClass().getName();
    }

    public String resultType() {
        return result == null ? null : result.getClass().getSimpleName();
    }

    public String errorMessage() {
        return error == null ? null : error.getMessage();
    }

    /** The SDK exception, when the error is one (carries status, request id, server message). */
    public RangerException rangerError() {
        return error instanceof RangerException e ? e : null;
    }

    /** 화면에 보여 줄 실패 정보. 성공했으면 null. */
    public Failure failure() {
        return error == null ? null : Failure.of(error);
    }

    /** Value as the page should treat it: the result if the call succeeded. */
    @SuppressWarnings("unchecked")
    public <T> T value() {
        return (T) result;
    }
}
