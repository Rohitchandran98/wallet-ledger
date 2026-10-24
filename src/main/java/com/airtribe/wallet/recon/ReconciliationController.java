package com.airtribe.wallet.recon;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.Json;
import com.airtribe.wallet.idempotency.IdempotencyKeyInterceptor;
import com.airtribe.wallet.idempotency.IdempotencyService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final Json json;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "reconciliation-manual");
        t.setDaemon(true);
        return t;
    });

    public ReconciliationController(ReconciliationService reconciliation, IdempotencyService idempotency,
                                    JdbcTemplate jdbc, Json json) {
        this.reconciliation = reconciliation;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 202 Accepted: the run is created and executes in the background. Add ?wait=true to block until it
     * finishes (used by tests and the demo).
     */
    @PostMapping("/admin/reconciliation/run")
    public ResponseEntity<String> run(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                      @RequestParam(defaultValue = "false") boolean wait) {
        var request = new IdempotencyService.Request(null, key, "POST", "/admin/reconciliation/run", Map.of());
        long[] created = new long[1];
        ApiResult result = idempotency.execute(request, () -> {
            created[0] = jdbc.queryForObject(
                    "INSERT INTO reconciliation_runs (triggered_by, status) VALUES ('admin', 'RUNNING') RETURNING id",
                    Long.class);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("run_id", created[0]);
            body.put("status", "RUNNING");
            body.put("href", "/admin/reconciliation/runs/" + created[0]);
            return new ApiResult(202, json.write(body));
        });
        if (created[0] != 0) { // a replay does not start a second run
            long runId = created[0];
            if (wait) {
                reconciliation.execute(runId);
                return runReport(runId);
            }
            executor.submit(() -> reconciliation.execute(runId));
        }
        return result.toResponse();
    }

    @GetMapping("/admin/reconciliation/runs/{id}")
    public ResponseEntity<String> get(@PathVariable long id) {
        return runReport(id);
    }

    private ResponseEntity<String> runReport(long id) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT * FROM reconciliation_runs WHERE id = ?", id);
        if (runs.isEmpty()) {
            throw ApiException.notFound("Reconciliation run", id);
        }
        Map<String, Object> out = new LinkedHashMap<>(runs.get(0));
        out.put("issues", jdbc.queryForList("""
                SELECT check_name, entity_type, entity_id, expected, actual, details
                  FROM reconciliation_issues WHERE run_id = ? ORDER BY id
                """, id));
        return new ApiResult(200, json.write(out)).toResponse();
    }
}
