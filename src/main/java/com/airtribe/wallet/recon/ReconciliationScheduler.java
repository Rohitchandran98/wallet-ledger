package com.airtribe.wallet.recon;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs in the background worker container (wallet.worker.enabled=true). The API container exposes the
 * manual trigger instead. Both go through the same job_locks row, so they can never overlap.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wallet.worker.enabled", havingValue = "true")
public class ReconciliationScheduler {

    private final ReconciliationService reconciliation;

    public ReconciliationScheduler(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${wallet.reconciliation.cron:0 30 2 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        reconciliation.runNow("scheduler");
    }
}
