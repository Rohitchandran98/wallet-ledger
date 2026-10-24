package com.airtribe.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.airtribe.wallet.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With a withdrawal fee configured, a withdrawal writes a second FEE transfer to the fee-revenue account. */
@TestPropertySource(properties = "wallet.fees.withdrawal-paise=200")
class WithdrawalFeeTest extends IntegrationTest {

    @Test
    void feeIsChargedAsALinkedFeeTransfer() {
        long asha = walletFor("Asha");
        topUp(asha, 10_000);

        Resp r = post("/transfers/withdraw", Map.of("wallet_id", asha, "amount", 5_000));
        assertThat(r.status()).isEqualTo(201);
        long feeId = r.json().get("fee_transfer_ids").get(0).asLong();

        assertThat(storedBalance(asha)).isEqualTo(4_800);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE system_code = 'FEE_REVENUE'", Long.class))
                .isEqualTo(200);
        Map<String, Object> fee = jdbc.queryForMap("SELECT type, amount, parent_transfer_id FROM transfers WHERE id = ?",
                feeId);
        assertThat(fee.get("type")).isEqualTo("FEE");
        assertThat(((Number) fee.get("parent_transfer_id")).longValue()).isEqualTo(r.id("transfer_id"));
        assertThat(totalDebits()).isEqualTo(totalCredits());

        // amount + fee must be covered
        Resp tooMuch = post("/transfers/withdraw", Map.of("wallet_id", asha, "amount", 4_700));
        assertThat(tooMuch.status()).isEqualTo(422);
    }
}
