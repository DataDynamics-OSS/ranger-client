package io.datadynamics.ranger.demo.config;

import io.datadynamics.ranger.demo.trace.TraceInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring MVC 설정입니다. 화면마다 SDK 요청을 모으는 {@link TraceInterceptor}를 등록합니다. */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {
    private final TraceInterceptor traceInterceptor;

    public WebConfig(TraceInterceptor traceInterceptor) {
        this.traceInterceptor = traceInterceptor;
    }

    /** 정적 리소스와 actuator는 Ranger를 부르지 않으므로(헬스 체크는 따로 이력에만 남습니다) 추적 대상에서 뺍니다. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(traceInterceptor).excludePathPatterns("/webjars/**", "/css/**", "/js/**", "/actuator/**");
    }
}
