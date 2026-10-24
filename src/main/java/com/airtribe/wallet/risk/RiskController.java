package com.airtribe.wallet.risk;

import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.Json;
import com.airtribe.wallet.idempotency.IdempotencyKeyInterceptor;
import com.airtribe.wallet.idempotency.IdempotencyService;
import com.airtribe.wallet.transfer.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RiskController {

    /** RELEASE moves the money; REVERSE (alias REJECT) cancels the held transfer — no money ever moved. */
    public record DecisionRequest(@NotBlank @Pattern(regexp = "RELEASE|REVERSE|REJECT") String decision,
                                  @Size(max = 280) String reason) {
    }

    private final JdbcTemplate jdbc;
    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final Json json;

    public RiskController(JdbcTemplate jdbc, TransferService transfers, IdempotencyService idempotency, Json json) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping("/admin/risk/queue")
    public ResponseEntity<String> queue(@RequestParam(defaultValue = "100") int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> held = jdbc.queryForList("""
                SELECT t.id AS transfer_id, t.status, t.amount, t.from_account_id, t.to_account_id, t.note, t.created_at
                  FROM transfers t
                 WHERE t.status = 'HELD'
                 ORDER BY t.created_at
                 LIMIT ?
                """, capped);
        List<Map<String, Object>> flagged = jdbc.queryForList("""
                SELECT f.transfer_id, t.status, t.amount, r.code AS rule, f.action, f.details::text AS evidence,
                       f.llm_label, f.llm_explanation, f.created_at
                  FROM risk_flags f
                  JOIN risk_rules r ON r.id = f.rule_id
                  JOIN transfers t  ON t.id = f.transfer_id
                 ORDER BY f.created_at DESC, f.id DESC
                 LIMIT ?
                """, capped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("held", held);
        out.put("flags", flagged);
        return new ApiResult(200, json.write(out)).toResponse();
    }

    @PostMapping("/admin/risk/{transferId}/decision")
    public ResponseEntity<String> decide(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                         @RequestHeader(value = "X-Actor", required = false) String actor,
                                         @PathVariable long transferId,
                                         @Valid @RequestBody DecisionRequest body) {
        String who = "admin:" + (actor == null || actor.isBlank() ? "unknown" : actor.trim());
        var request = new IdempotencyService.Request(null, key, "POST",
                "/admin/risk/" + transferId + "/decision", body);
        return idempotency.execute(request, () -> "RELEASE".equals(body.decision())
                ? transfers.release(transferId, body.reason(), who)
                : transfers.reject(transferId, body.reason(), who)).toResponse();
    }
}
