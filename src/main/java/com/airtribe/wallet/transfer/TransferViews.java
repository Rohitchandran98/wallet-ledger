package com.airtribe.wallet.transfer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only JSON view of a transfer, shared by write responses and GET /transfers/{id}. */
@Component
public class TransferViews {

    private final JdbcTemplate jdbc;

    public TransferViews(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> transferJson(long transferId, List<Long> relatedTransferIds) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT id, type, status, amount, from_account_id, to_account_id,
                       reverses_transfer_id, parent_transfer_id, note, created_at
                  FROM transfers WHERE id = ?
                """, transferId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transfer_id", t.get("id"));
        out.put("type", t.get("type"));
        out.put("status", t.get("status"));
        out.put("amount", t.get("amount"));
        out.put("currency", "INR");
        out.put("from_account_id", t.get("from_account_id"));
        out.put("to_account_id", t.get("to_account_id"));
        if (t.get("reverses_transfer_id") != null) {
            out.put("reverses_transfer_id", t.get("reverses_transfer_id"));
        }
        if (!relatedTransferIds.isEmpty()) {
            out.put("fee_transfer_ids", relatedTransferIds);
        }
        out.put("note", t.get("note"));
        out.put("created_at", t.get("created_at"));
        out.put("risk_flags", jdbc.queryForList("""
                SELECT r.code AS rule, f.action
                  FROM risk_flags f JOIN risk_rules r ON r.id = f.rule_id
                 WHERE f.transfer_id = ? ORDER BY f.id
                """, transferId));
        return out;
    }
}
