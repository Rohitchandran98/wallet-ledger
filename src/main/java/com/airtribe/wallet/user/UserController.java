package com.airtribe.wallet.user;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.common.ApiResult;
import com.airtribe.wallet.common.Json;
import com.airtribe.wallet.idempotency.IdempotencyKeyInterceptor;
import com.airtribe.wallet.idempotency.IdempotencyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "must be 8-15 digits, optional +") String phone,
            @Pattern(regexp = "PENDING|VERIFIED|REJECTED") String kycStatus) {
    }

    public record CreateWalletRequest(@NotBlank @Size(max = 50) String name) {
    }

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final Json json;

    public UserController(JdbcTemplate jdbc, IdempotencyService idempotency, Json json) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/users")
    public ResponseEntity<String> createUser(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                             @Valid @RequestBody CreateUserRequest body) {
        var request = new IdempotencyService.Request(null, key, "POST", "/users", body);
        return idempotency.execute(request, () -> {
            String kyc = body.kycStatus() == null ? "PENDING" : body.kycStatus();
            try {
                Map<String, Object> row = jdbc.queryForMap("""
                        INSERT INTO users (name, email, phone, kyc_status)
                        VALUES (?, ?, ?, ?)
                        RETURNING id, name, email, phone, kyc_status, created_at
                        """, body.name().trim(), body.email().trim(), body.phone().trim(), kyc);
                return new ApiResult(201, json.write(userJson(row)));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("USER_EXISTS", "A user with this email or phone already exists");
            }
        }).toResponse();
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<String> getUser(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, email, phone, kyc_status, created_at FROM users WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("User", id);
        }
        Map<String, Object> user = userJson(rows.get(0));
        user.put("wallets", jdbc.queryForList(
                "SELECT id, name, currency, balance, created_at FROM accounts WHERE user_id = ? ORDER BY id", id));
        return new ApiResult(200, json.write(user)).toResponse();
    }

    @PostMapping("/users/{id}/wallets")
    public ResponseEntity<String> openWallet(@RequestHeader(IdempotencyKeyInterceptor.HEADER) String key,
                                             @PathVariable long id,
                                             @Valid @RequestBody CreateWalletRequest body) {
        requireUser(id);
        var request = new IdempotencyService.Request(id, key, "POST", "/users/" + id + "/wallets", body);
        return idempotency.execute(request, () -> {
            Map<String, Object> row = jdbc.queryForMap("""
                    INSERT INTO accounts (user_id, kind, name, currency)
                    VALUES (?, 'USER_WALLET', ?, 'INR')
                    RETURNING id, user_id, name, currency, balance, created_at
                    """, id, body.name().trim());
            Map<String, Object> wallet = new LinkedHashMap<>();
            wallet.put("wallet_id", row.get("id"));
            wallet.put("user_id", row.get("user_id"));
            wallet.put("name", row.get("name"));
            wallet.put("currency", row.get("currency"));
            wallet.put("balance", row.get("balance"));
            wallet.put("created_at", row.get("created_at"));
            return new ApiResult(201, json.write(wallet));
        }).toResponse();
    }

    private void requireUser(long id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, id);
        if (n == null || n == 0) {
            throw ApiException.notFound("User", id);
        }
    }

    private static Map<String, Object> userJson(Map<String, Object> row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("user_id", row.get("id"));
        user.put("name", row.get("name"));
        user.put("email", row.get("email"));
        user.put("phone", row.get("phone"));
        user.put("kyc_status", row.get("kyc_status"));
        user.put("created_at", row.get("created_at"));
        return user;
    }
}
