package com.airtribe.wallet.common;

import com.airtribe.wallet.idempotency.IdempotencyKeyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final IdempotencyKeyInterceptor idempotencyKeyInterceptor;

    public WebConfig(IdempotencyKeyInterceptor idempotencyKeyInterceptor) {
        this.idempotencyKeyInterceptor = idempotencyKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(idempotencyKeyInterceptor).excludePathPatterns("/actuator/**");
    }
}
