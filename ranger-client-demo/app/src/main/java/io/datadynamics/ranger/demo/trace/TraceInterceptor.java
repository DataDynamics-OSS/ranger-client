package io.datadynamics.ranger.demo.trace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 웹 요청마다 그 처리 중에 SDK가 보낸 HTTP 요청을 모아, 뷰에서 {@code trace}라는 요청 속성으로 쓸 수 있게 합니다.
 * <p>
 * 컨트롤러가 실행되기 전에 {@link SdkCalls#beginPage()}로 수집을 시작하고, 요청 처리가 끝나면
 * (예외가 나도) {@link SdkCalls#endPage()}로 정리합니다.
 */
@Component
public class TraceInterceptor implements HandlerInterceptor {

    /** 수집한 요청 목록을 담는 서블릿 요청 속성 이름입니다. */
    public static final String ATTRIBUTE = "trace";

    private final SdkCalls calls;

    public TraceInterceptor(SdkCalls calls) {
        this.calls = calls;
    }

    /** 수집 목록은 이후 요청이 계속 추가되는 살아 있는 목록이므로, 뷰를 그릴 때는 컨트롤러가 보낸 요청이 모두 들어 있습니다. */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTRIBUTE, calls.beginPage());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        calls.endPage();
    }
}
