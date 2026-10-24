package com.airtribe.wallet.risk;

import com.airtribe.wallet.common.ApiException;
import com.airtribe.wallet.common.Json;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fraud rules. Each rule is one SQL query that runs inside the transfer transaction, after the payer's
 * wallet row is locked and before commit. Thresholds come from the risk_rules table.
 *
 * <pre>
 * rule              action  index that keeps it fast
 * VELOCITY          HOLD    transfers_from_created_idx (from_account_id, created_at)
 * AMOUNT_SPIKE      HOLD    transfers_from_created_idx ... INCLUDE (amount, status)  -> index-only scan
 * NEW_PAYEE_LARGE   FLAG    transfers_from_to_idx (from_account_id, to_account_id)
 * </pre>
 */
@Service
@Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
public class RiskEngine {

    public record RuleHit(long ruleId, String code, String action, Map<String, Object> details) {
    }

    record Rule(long id, String code, String action, Integer windowMinutes, Integer maxCount,
                BigDecimal multiplier, Integer lookbackDays, Long amountThreshold) {
    }

    private final JdbcTemplate jdbc;
    private final Json json;

    public RiskEngine(JdbcTemplate jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<RuleHit> evaluate(long fromWalletId, long toWalletId, long amount) {
        List<RuleHit> hits = new ArrayList<>();
        for (Rule rule : enabledRules()) {
            RuleHit hit = switch (rule.code()) {
                case "VELOCITY" -> velocity(rule, fromWalletId);
                case "AMOUNT_SPIKE" -> amountSpike(rule, fromWalletId, amount);
                case "NEW_PAYEE_LARGE" -> newPayeeLarge(rule, fromWalletId, toWalletId, amount);
                default -> null;
            };
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    /** More than max_count outgoing transfers (completed or held) from this wallet in the last window_minutes. */
    private RuleHit velocity(Rule rule, long walletId) {
        int recent = jdbc.queryForObject("""
                SELECT count(*)
                  FROM transfers
                 WHERE from_account_id = ?
                   AND created_at > now() - make_interval(mins => ?)
                   AND type IN ('PEER', 'WITHDRAWAL')
                   AND status IN ('COMPLETED', 'HELD', 'REVERSED')
                """, Integer.class, walletId, rule.windowMinutes());
        // this transfer would be number (recent + 1)
        if (recent + 1 > rule.maxCount()) {
            return hit(rule, Map.of("recent_outgoing_transfers", recent, "window_minutes", rule.windowMinutes(),
                    "max_count", rule.maxCount()));
        }
        return null;
    }

    /** Amount is over multiplier x the wallet's average outgoing transfer over the last lookback_days. */
    private RuleHit amountSpike(Rule rule, long walletId, long amount) {
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT avg(amount)::bigint AS avg_amount, count(*) AS n
                  FROM transfers
                 WHERE from_account_id = ?
                   AND created_at > now() - make_interval(days => ?)
                   AND type IN ('PEER', 'WITHDRAWAL')
                   AND status IN ('COMPLETED', 'REVERSED')
                """, walletId, rule.lookbackDays());
        Number avg = (Number) stats.get("avg_amount");
        if (avg == null) {
            return null; // no history yet: nothing to compare against
        }
        BigDecimal limit = rule.multiplier().multiply(BigDecimal.valueOf(avg.longValue()));
        if (BigDecimal.valueOf(amount).compareTo(limit) > 0) {
            return hit(rule, Map.of("average_amount", avg.longValue(), "multiplier", rule.multiplier(),
                    "history_size", stats.get("n"), "amount", amount));
        }
        return null;
    }

    /** First ever completed transfer from this wallet to this recipient, and above amount_threshold. */
    private RuleHit newPayeeLarge(Rule rule, long fromWalletId, long toWalletId, long amount) {
        if (amount <= rule.amountThreshold()) {
            return null;
        }
        boolean paidBefore = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1
                                 FROM transfers
                                WHERE from_account_id = ?
                                  AND to_account_id = ?
                                  AND status IN ('COMPLETED', 'REVERSED'))
                """, Boolean.class, fromWalletId, toWalletId));
        if (!paidBefore) {
            return hit(rule, Map.of("amount", amount, "threshold", rule.amountThreshold()));
        }
        return null;
    }

    public void recordFlags(long transferId, List<RuleHit> hits) {
        for (RuleHit h : hits) {
            jdbc.update("""
                    INSERT INTO risk_flags (transfer_id, rule_id, action, details)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, transferId, h.ruleId(), h.action(), json.write(h.details()));
        }
    }

    private List<Rule> enabledRules() {
        return jdbc.query("""
                SELECT id, code, action, window_minutes, max_count, multiplier, lookback_days, amount_threshold
                  FROM risk_rules
                 WHERE enabled
                 ORDER BY id
                """, (rs, i) -> new Rule(rs.getLong("id"), rs.getString("code"), rs.getString("action"),
                (Integer) rs.getObject("window_minutes"), (Integer) rs.getObject("max_count"),
                rs.getBigDecimal("multiplier"), (Integer) rs.getObject("lookback_days"),
                (Long) rs.getObject("amount_threshold")));
    }

    private static RuleHit hit(Rule rule, Map<String, Object> details) {
        Map<String, Object> d = new LinkedHashMap<>(details);
        return new RuleHit(rule.id(), rule.code(), rule.action(), d);
    }
}
