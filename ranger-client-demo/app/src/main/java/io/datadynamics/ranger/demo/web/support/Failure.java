package io.datadynamics.ranger.demo.web.support;

import io.datadynamics.ranger.client.core.RangerException;

/**
 * 사용자에게 보여 줄 형태로 바꾼 예외입니다. 이 데모는 클라이언트가 문제를 어떻게 알려 주는지 보여 주는 것이
 * 목적이므로, SDK 예외의 타입(클래스)을 함께 보여 주는 것이 중요합니다.
 *
 * @param type          예외 클래스의 전체 이름
 * @param status        Ranger가 돌려준 HTTP 상태 코드(있을 때만)
 * @param server        요청을 받은 서버 주소(있을 때만)
 * @param request       실패한 요청의 "메서드 경로"(있을 때만)
 * @param requestId     실패한 요청의 {@code X-Request-Id}(있을 때만)
 * @param message       예외 메시지
 * @param serverMessage Ranger가 본문에 담아 보낸 오류 메시지(있을 때만)
 */
public record Failure(String type, Integer status, String server, String request, String requestId, String message,
                      String serverMessage) {

    public static Failure of(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        if (e instanceof RangerException r) {
            String request = r.getMethod() == null ? null : r.getMethod() + " " + r.getPath();
            return new Failure(e.getClass().getName(), r.getStatus(), r.getServer(), request, r.getRequestId(), message,
                    r.getServerMessage());
        }
        return new Failure(e.getClass().getName(), null, null, null, null, message, null);
    }

    /** 패키지를 뺀 예외 클래스 이름입니다. 예: {@code RangerNotFoundException} */
    public String simpleType() {
        return type.substring(type.lastIndexOf('.') + 1);
    }
}
