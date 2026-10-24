package com.airtribe.wallet.idempotency;

import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.ErrorBodies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Every POST must carry an Idempotency-Key header holding a UUID; otherwise 400 before any work is done. */
@Component
public class IdempotencyKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER = "Idempotency-Key";

    private final ErrorBodies errors;

    public IdempotencyKeyInterceptor(ErrorBodies errors) {
        this.errors = errors;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            return reject(response, "IDEMPOTENCY_KEY_REQUIRED", "Every POST needs an Idempotency-Key header (a UUID)");
        }
        if (parse(key) == null) {
            return reject(response, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be a UUID");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, String code, String message) throws Exception {
        ApiResult body = errors.of(400, code, message);
        response.setStatus(400);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body.body());
        return false;
    }

    public static UUID parse(String key) {
        try {
            return UUID.fromString(key.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
