package io.datadynamics.ranger.demo.trace;

import java.net.URI;
import java.time.Instant;

/**
 * SDK 클라이언트의 HTTP 시도 하나를 기록한 값입니다. 재시도와 장애 조치(failover)는 시도마다 따로 기록됩니다.
 *
 * @param id           기록 순서대로 1부터 매기는 일련번호
 * @param client       요청을 보낸 클라이언트 이름(admin, pdp, kms, lab-…)
 * @param server       SDK가 알려 준 서버 종류(admin, pdp, kms)
 * @param uri          선택된 서버와 쿼리 문자열을 포함한 전체 URI
 * @param attempt      같은 호출 안에서 몇 번째 시도인지(1부터)
 * @param requestId    SDK가 붙인 {@code X-Request-Id}
 * @param status       HTTP 상태 코드. 응답을 받지 못했으면 null
 * @param error        응답을 받지 못했을 때의 실패 내용("예외 클래스 이름: 메시지")
 * @param requestBody  요청 본문(비밀번호는 가리고, 길면 잘림). 없으면 null
 * @param responseBody 응답 본문(길면 잘림). 없으면 null
 */
public record HttpAttempt(long id, Instant at, String client, String server, String method, URI uri, int attempt,
                          String requestId, Integer status, String error, long elapsedMs,
                          String requestBody, String responseBody) {

    public boolean failed() {
        return error != null || status == null || status >= 400;
    }

    /** 경로와 쿼리 문자열. 표에서 짧게 보여 줄 때 씁니다. */
    public String target() {
        String q = uri.getRawQuery();
        return uri.getRawPath() + (q == null ? "" : "?" + q);
    }

    public String origin() {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** 요청 본문을 Monaco에서 어떤 언어로 보여 줄지: JSON처럼 보이면 json, 아니면 text. */
    public String requestLang() {
        return lang(requestBody);
    }

    public String responseLang() {
        return lang(responseBody);
    }

    private static String lang(String body) {
        String t = body == null ? "" : body.stripLeading();
        return t.startsWith("{") || t.startsWith("[") ? "json" : "text";
    }
}
