package io.datadynamics.ranger.demo.web.support;

import io.datadynamics.ranger.client.core.RangerException;
import io.datadynamics.ranger.client.core.RangerNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * {@code SdkCalls.run} 밖에서 난 SDK 예외와 잘못된 입력을 오류 화면으로 보여 줍니다.
 * <p>
 * 응답 상태는 {@link IllegalArgumentException}이면 400, {@link RangerNotFoundException}이면 404,
 * 그 밖의 SDK 예외는 Ranger 쪽 문제이므로 502(Bad Gateway)입니다.
 */
@ControllerAdvice
class ErrorAdvice {

    @ExceptionHandler({RangerException.class, IllegalArgumentException.class})
    ModelAndView failure(RuntimeException e) {
        ModelAndView view = new ModelAndView("failure");
        view.addObject("failure", Failure.of(e));
        view.setStatus(e instanceof IllegalArgumentException ? HttpStatus.BAD_REQUEST
                : e instanceof RangerNotFoundException ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY);
        return view;
    }
}
