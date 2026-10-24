package com.airtribe.wallet.idempotency;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.ErrorBodies;
import com.airtribe.wallet.common.Json;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a write request at most once per (user, Idempotency-Key).
 *
 * <pre>
 * BEGIN (READ COMMITTED)
 *   INSERT INTO idempotency_keys ... ON CONFLICT DO NOTHING      -- the UNIQUE (user_id, key) constraint is the guard
 *   -- conflict?  seen before -> ROLLBACK and replay / 409 / 422
 *   SAVEPOINT work
 *     ... the transfer (locks, entries, balances) ...
 *   -- business error? ROLLBACK TO SAVEPOINT work (no money moved) but keep the key
 *   UPDATE idempotency_keys SET status='COMPLETED', response_code=?, response_body=?
 * COMMIT                                                         -- key, response and money become visible together
 * </pre>
 *
 * Two identical requests arriving at the same instant: the second INSERT blocks on the first one's
 * uncommitted unique-index entry. When the first commits, the second sees the conflict and replays the
 * stored response. If the first is still running after {@code lock_timeout}, the second gets 409.
 */
@Service
public class IdempotencyService {

    private static final String KEY_LOCK_TIMEOUT = "5s";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Json json;
    private final ErrorBodies errors;

    public IdempotencyService(JdbcTemplate jdbc, PlatformTransactionManager txManager, Json json, ErrorBodies errors) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.json = json;
        this.errors = errors;
    }

    public record Request(Long userId, String key, String method, String path, Object body) {
    }

    private record StoredKey(String requestHash, String status, Integer responseCode, String responseBody) {
    }

    public ApiResult execute(Request request, Supplier<ApiResult> work) {
        UUID key = IdempotencyKeyInterceptor.parse(request.key());
        if (key == null) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be a UUID");
        }
        String hash = json.requestHash(request.method(), request.path(), request.body());

        try {
            return tx.execute(status -> {
                // 1. Claim the key first. Wait at most KEY_LOCK_TIMEOUT for a concurrent twin.
                jdbc.execute("SET LOCAL lock_timeout = '" + KEY_LOCK_TIMEOUT + "'");
                List<Long> inserted = jdbc.queryForList("""
                        INSERT INTO idempotency_keys (user_id, idempotency_key, request_method, request_path,
                                                      request_hash, status)
                        VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS')
                        ON CONFLICT ON CONSTRAINT idempotency_keys_user_key_uq DO NOTHING
                        RETURNING id
                        """, Long.class, request.userId(), key, request.method(), request.path(), hash);
                jdbc.execute("SET LOCAL lock_timeout = DEFAULT");

                if (inserted.isEmpty()) {
                    // 2a. Seen this key before: nothing of ours to keep.
                    status.setRollbackOnly();
                    return replay(request.userId(), key, hash);
                }
                long keyId = inserted.get(0);

                // 2b. First time: do the work behind a savepoint so a business error leaves no trace
                //     except the stored error response.
                Object savepoint = status.createSavepoint();
                ApiResult result;
                try {
                    result = work.get();
                    status.releaseSavepoint(savepoint);
                } catch (ApiException e) {
                    status.rollbackToSavepoint(savepoint);
                    result = errors.of(e);
                }

                // 3. Save the response on the key row; COMMIT makes money + response visible atomically.
                jdbc.update("""
                        UPDATE idempotency_keys
                           SET status = 'COMPLETED', response_code = ?, response_body = ?, completed_at = now()
                         WHERE id = ?
                        """, result.status(), result.body(), keyId);
                return result;
            });
        } catch (DataAccessException e) {
            if (isLockTimeout(e)) {
                return errors.of(409, "REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is still being processed; retry shortly");
            }
            throw e;
        }
    }

    private ApiResult replay(Long userId, UUID key, String hash) {
        StoredKey stored = jdbc.queryForObject("""
                SELECT request_hash, status, response_code, response_body
                  FROM idempotency_keys
                 WHERE user_id IS NOT DISTINCT FROM ? AND idempotency_key = ?
                """, (rs, i) -> new StoredKey(rs.getString(1), rs.getString(2),
                        (Integer) rs.getObject(3), rs.getString(4)), userId, key);

        if (!stored.requestHash().equals(hash)) {
            return errors.of(422, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request body");
        }
        if (!"COMPLETED".equals(stored.status())) {
            return errors.of(409, "REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed; retry shortly");
        }
        return new ApiResult(stored.responseCode(), stored.responseBody());
    }

    private static boolean isLockTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
