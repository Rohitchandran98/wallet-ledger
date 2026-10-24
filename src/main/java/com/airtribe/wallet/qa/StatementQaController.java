package com.airtribe.wallet.qa;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.idempotency.IdempotencyKeyInterceptor;
import com.airtribe.wallet.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatementQaController {

    public record AskRequest(@NotBlank @Size(max = 500) String question) {
    }

    private final StatementQaService qa;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;

    public StatementQaController(StatementQaService qa, IdempotencyService idempotency, JdbcTemplate jdbc) {
        this.qa = qa;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
    }

    @PostMapping("/users/{id}/statement/ask")
    public ResponseEntity<String> ask(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                      @PathVariable long id,
                                      @Valid @RequestBody AskRequest body) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("User", id);
        }
        // The model is called before the DB transaction opens, so no connection is held during the network call.
        String llmOutput = qa.interpret(body.question());
        var request = new IdempotencyService.Request(id, key, "POST", "/users/" + id + "/statement/ask", body);
        return idempotency.execute(request, () -> qa.answer(id, body.question(), llmOutput)).toResponse();
    }
}
