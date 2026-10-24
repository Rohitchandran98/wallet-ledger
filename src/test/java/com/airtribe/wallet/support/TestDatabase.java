package com.airtribe.wallet.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;

/**
 * One real PostgreSQL for the whole test run.
 * <ul>
 *   <li>TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD set (CI, docker compose): use that server.</li>
 *   <li>otherwise: start embedded PostgreSQL 16 binaries (no Docker needed).</li>
 * </ul>
 */
public final class TestDatabase {

    public record Info(String url, String user, String password) {
    }

    private static Info info;

    private TestDatabase() {
    }

    public static synchronized Info get() {
        if (info != null) {
            return info;
        }
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            info = new Info(url, env("TEST_DB_USER", "wallet"), env("TEST_DB_PASSWORD", "wallet"));
            return info;
        }
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder()
                    .setServerConfig("max_connections", "300")
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }));
            info = new Info(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
            return info;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start embedded PostgreSQL", e);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
