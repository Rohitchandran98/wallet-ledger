package com.airtribe.wallet.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the real application on a random port against a real PostgreSQL. Each test starts from an empty,
 * freshly migrated schema. Requests go over real HTTP so concurrency tests exercise the whole stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    @TestConfiguration
    public static class TestBeans {
        /** Replaces the production "llmClient" bean (mock/anthropic) for the whole test context. */
        @Bean(name = "llmClient")
        @Primary
        public ScriptedLlmClient llmClient(Clock clock) {
            return new ScriptedLlmClient(clock);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.Info db = TestDatabase.get();
        registry.add("spring.datasource.url", db::url);
        registry.add("spring.datasource.username", db::user);
        registry.add("spring.datasource.password", db::password);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
    }

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(java.util.concurrent.Executors.newCachedThreadPool())
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected Flyway flyway;

    @Autowired
    protected ScriptedLlmClient llm;

    @BeforeEach
    void freshSchema() {
        flyway.clean();
        flyway.migrate();
        llm.reset();
    }

    // ------------------------------------------------------------------ HTTP helpers

    public record Resp(int status, String raw, JsonNode json) {
        public long id(String field) {
            return json.get(field).asLong();
        }
    }

    protected Resp get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    protected Resp post(String path, Object body) {
        return post(path, body, UUID.randomUUID().toString());
    }

    protected Resp post(String path, Object body, String idempotencyKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)));
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return send(b.build());
    }

    private Resp send(HttpRequest request) {
        try {
            HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node = r.body() == null || r.body().isBlank() ? null : MAPPER.readTree(r.body());
            return new Resp(r.statusCode(), r.body(), node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String write(Object body) {
        try {
            return body instanceof String s ? s : MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ domain helpers

    private static int userSeq = 0;

    protected long createUser(String name) {
        int n = ++userSeq;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("email", name.toLowerCase().replace(' ', '.') + n + "@example.com");
        body.put("phone", "+9198" + String.format("%08d", n));
        Resp r = post("/users", body);
        if (r.status() != 201) {
            throw new IllegalStateException("create user failed: " + r.raw());
        }
        return r.id("user_id");
    }

    protected long openWallet(long userId) {
        Resp r = post("/users/" + userId + "/wallets", Map.of("name", "Main"));
        if (r.status() != 201) {
            throw new IllegalStateException("open wallet failed: " + r.raw());
        }
        return r.id("wallet_id");
    }

    protected long walletFor(String name) {
        return openWallet(createUser(name));
    }

    protected Resp topUp(long walletId, long amount) {
        return post("/transfers/topup", Map.of("wallet_id", walletId, "amount", amount));
    }

    protected Resp transfer(long from, long to, long amount) {
        return post("/transfers", Map.of("from_wallet_id", from, "to_wallet_id", to, "amount", amount));
    }

    protected long storedBalance(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    protected long computedBalance(long accountId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
                  FROM ledger_entries WHERE account_id = ?
                """, Long.class, accountId);
    }

    protected long totalDebits() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'DEBIT'", Long.class);
    }

    protected long totalCredits() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE direction = 'CREDIT'", Long.class);
    }

    protected void disableRiskRules() {
        jdbc.update("UPDATE risk_rules SET enabled = false");
    }
}
