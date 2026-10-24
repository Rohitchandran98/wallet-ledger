package com.airtribe.wallet.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds the single error shape used everywhere: {"code", "message", "request_id"}. */
@Component
public class ErrorBodies {

    private final Json json;

    public ErrorBodies(Json json) {
        this.json = json;
    }

    public ApiResult of(ApiException e) {
        return of(e.status().value(), e.code(), e.getMessage());
    }

    public ApiResult of(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("request_id", RequestIdFilter.current());
        return new ApiResult(status, json.write(body));
    }
}
