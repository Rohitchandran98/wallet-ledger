package com.airtribe.wallet.transfer;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.Json;
import com.airtribe.wallet.idempotency.IdempotencyKeyInterceptor;
import com.airtribe.wallet.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    public record TopUpRequest(@NotNull Long walletId,
                               @NotNull @Positive @Max(TransferService.MAX_AMOUNT) Long amount,
                               @Size(max = 280) String note) {
    }

    public record TransferRequest(@NotNull Long fromWalletId,
                                  @NotNull Long toWalletId,
                                  @NotNull @Positive @Max(TransferService.MAX_AMOUNT) Long amount,
                                  @Size(max = 280) String note) {
    }

    public record WithdrawRequest(@NotNull Long walletId,
                                  @NotNull @Positive @Max(TransferService.MAX_AMOUNT) Long amount,
                                  @Size(max = 280) String note) {
    }

    public record ReverseRequest(@Size(max = 280) String reason) {
    }

    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final Json json;
    private final TransferViews views;

    public TransferController(TransferService transfers, IdempotencyService idempotency, JdbcTemplate jdbc, Json json,
                              TransferViews views) {
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.json = json;
        this.views = views;
    }

    @PostMapping("/transfers/topup")
    public ResponseEntity<String> topUp(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                        @Valid @RequestBody TopUpRequest body) {
        long owner = walletOwner(body.walletId());
        var request = new IdempotencyService.Request(owner, key, "POST", "/transfers/topup", body);
        return idempotency.execute(request,
                () -> transfers.topUp(body.walletId(), body.amount(), body.note(), "user:" + owner)).toResponse();
    }

    @PostMapping("/transfers")
    public ResponseEntity<String> transfer(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                           @Valid @RequestBody TransferRequest body) {
        long owner = walletOwner(body.fromWalletId());
        var request = new IdempotencyService.Request(owner, key, "POST", "/transfers", body);
        return idempotency.execute(request, () -> transfers.transfer(body.fromWalletId(), body.toWalletId(),
                body.amount(), body.note(), "user:" + owner)).toResponse();
    }

    @PostMapping("/transfers/withdraw")
    public ResponseEntity<String> withdraw(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                           @Valid @RequestBody WithdrawRequest body) {
        long owner = walletOwner(body.walletId());
        var request = new IdempotencyService.Request(owner, key, "POST", "/transfers/withdraw", body);
        return idempotency.execute(request,
                () -> transfers.withdraw(body.walletId(), body.amount(), body.note(), "user:" + owner)).toResponse();
    }

    /** Reversals are an operations action (no owning user), so the key is scoped to user_id NULL. */
    @PostMapping("/transfers/{id}/reverse")
    public ResponseEntity<String> reverse(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                          @RequestHeader(value = "X-Actor", required = false) String actor,
                                          @PathVariable long id,
                                          @Valid @RequestBody(required = false) ReverseRequest body) {
        ReverseRequest req = body == null ? new ReverseRequest(null) : body;
        String who = actor == null || actor.isBlank() ? "ops" : actor.trim();
        var request = new IdempotencyService.Request(null, key, "POST", "/transfers/" + id + "/reverse", req);
        return idempotency.execute(request, () -> transfers.reverse(id, req.reason(), who)).toResponse();
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<String> get(@PathVariable long id) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM transfers WHERE id = ?", Integer.class, id);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("Transfer", id);
        }
        Map<String, Object> out = views.transferJson(id, List.of());
        out.put("entries", jdbc.queryForList("""
                SELECT id AS entry_id, account_id, direction, amount, created_at
                  FROM ledger_entries WHERE transfer_id = ? ORDER BY id
                """, id));
        out.put("status_history", jdbc.queryForList("""
                SELECT from_status, to_status, changed_by, reason, changed_at
                  FROM transfer_status_history WHERE transfer_id = ? ORDER BY id
                """, id));
        List<Map<String, Object>> reversal = jdbc.queryForList(
                "SELECT id FROM transfers WHERE reverses_transfer_id = ?", id);
        if (!reversal.isEmpty()) {
            out.put("reversed_by_transfer_id", reversal.get(0).get("id"));
        }
        return new ApiResult(200, json.write(out)).toResponse();
    }

    private long walletOwner(long walletId) {
        List<Long> owner = jdbc.queryForList(
                "SELECT user_id FROM accounts WHERE id = ? AND kind = 'USER_WALLET'", Long.class, walletId);
        if (owner.isEmpty()) {
            throw ApiException.notFound("Wallet", walletId);
        }
        return owner.get(0);
    }
}
