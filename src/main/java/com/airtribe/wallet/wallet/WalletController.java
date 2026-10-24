package com.airtribe.wallet.wallet;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.Json;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {

    private static final int MAX_LIMIT = 200;

    private static final String STATEMENT_COLUMNS = """
            SELECT e.id AS entry_id, e.transfer_id, e.direction, e.amount, e.created_at,
                   t.type, t.status AS transfer_status, t.note,
                   CASE WHEN e.direction = 'DEBIT' THEN t.to_account_id ELSE t.from_account_id END
                       AS counterparty_account_id
              FROM ledger_entries e
              JOIN transfers t ON t.id = e.transfer_id
            """;

    private final JdbcTemplate jdbc;
    private final Json json;

    public WalletController(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/wallets/{id}/balance")
    public ResponseEntity<String> balance(@PathVariable long id) {
        Map<String, Object> wallet = wallet(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wallet_id", id);
        out.put("user_id", wallet.get("user_id"));
        out.put("balance", wallet.get("balance"));
        out.put("currency", wallet.get("currency"));
        return new ApiResult(200, json.write(out)).toResponse();
    }

    /**
     * Newest first. Two modes:
     * <ul>
     *   <li>offset (required by the brief): {@code ?limit=50&offset=100} — ORDER BY created_at DESC, id DESC LIMIT/OFFSET.
     *       Postgres must still walk and discard every skipped row, so deep pages get slower linearly.</li>
     *   <li>cursor (bonus): {@code ?limit=50&cursor=...} — keyset on (created_at, id); every page is one index seek.</li>
     * </ul>
     */
    @GetMapping("/wallets/{id}/statement")
    public ResponseEntity<String> statement(@PathVariable long id,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(defaultValue = "0") long offset,
                                            @RequestParam(required = false) String cursor) {
        wallet(id);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw ApiException.badRequest("INVALID_OFFSET", "offset must be >= 0");
        }

        List<Map<String, Object>> entries;
        Map<String, Object> page = new LinkedHashMap<>();
        if (cursor != null && !cursor.isBlank()) {
            Cursor c = Cursor.decode(cursor);
            entries = jdbc.queryForList(STATEMENT_COLUMNS + """
                     WHERE e.account_id = ?
                       AND (e.created_at, e.id) < (?, ?)
                     ORDER BY e.created_at DESC, e.id DESC
                     LIMIT ?
                    """, id, Timestamp.from(c.createdAt()), c.id(), limit);
            page.put("mode", "cursor");
        } else {
            entries = jdbc.queryForList(STATEMENT_COLUMNS + """
                     WHERE e.account_id = ?
                     ORDER BY e.created_at DESC, e.id DESC
                     LIMIT ? OFFSET ?
                    """, id, limit, offset);
            page.put("mode", "offset");
            page.put("offset", offset);
            page.put("next_offset", entries.size() == limit ? offset + limit : null);
        }
        page.put("limit", limit);
        page.put("next_cursor", entries.size() == limit ? Cursor.encode(entries.get(entries.size() - 1)) : null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wallet_id", id);
        out.put("entries", entries);
        out.put("page", page);
        return new ApiResult(200, json.write(out)).toResponse();
    }

    private Map<String, Object> wallet(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, balance, currency FROM accounts WHERE id = ? AND kind = 'USER_WALLET'", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Wallet", id);
        }
        return rows.get(0);
    }

    record Cursor(Instant createdAt, long id) {

        static String encode(Map<String, Object> lastEntry) {
            Instant at = ((Timestamp) lastEntry.get("created_at")).toInstant();
            String raw = at.toString() + "|" + lastEntry.get("entry_id");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|");
                return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw ApiException.badRequest("INVALID_CURSOR", "cursor is not valid");
            }
        }
    }
}
