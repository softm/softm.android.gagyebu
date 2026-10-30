package softm.android.gagyebu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.Optional;

public final class FinancialMessageParserTest {
    private static final long NOW = Instant.parse("2026-10-21T03:30:00Z").toEpochMilli();

    @Test
    public void parsesCardApprovalAndMerchant() {
        FinancialMessageParser.Result result = parsed(
            "신한카드",
            "승인 알림",
            "[Web발신]\n신한카드 승인 12,300원 스타벅스 10/21 12:30"
        );
        assertEquals("expense", result.kind);
        assertEquals(12_300L, result.amount);
        assertEquals("2026-10-21", result.date);
        assertEquals("card", result.accountType);
        assertTrue(result.merchant.contains("스타벅스"));
    }

    @Test
    public void prefersTransactionAmountOverBalance() {
        FinancialMessageParser.Result result = parsed(
            "우리은행",
            "입출금 알림",
            "100,000원 입금 홍길동\n잔액 5,200,000원"
        );
        assertEquals("income", result.kind);
        assertEquals(100_000L, result.amount);
        assertEquals("bank", result.accountType);
    }

    @Test
    public void detectsWithdrawalAndRefund() {
        FinancialMessageParser.Result withdrawal = parsed(
            "KB국민은행",
            "",
            "출금 45,000원 편의점 잔액 800,000원"
        );
        FinancialMessageParser.Result refund = parsed(
            "현대카드",
            "",
            "현대카드 승인취소 21,900원 온라인몰"
        );
        assertEquals("expense", withdrawal.kind);
        assertEquals(45_000L, withdrawal.amount);
        assertEquals("refund", refund.kind);
        assertEquals(21_900L, refund.amount);
    }

    @Test
    public void ignoresNonFinancialOtpAndAds() {
        assertFalse(FinancialMessageParser.parse(
            "서비스",
            "",
            "인증번호 123456을 입력하세요. 결제 금액 1,000원",
            NOW
        ).isPresent());
        assertFalse(FinancialMessageParser.parse(
            "카드사",
            "",
            "[광고] 오늘 결제하면 10,000원 할인",
            NOW
        ).isPresent());
        assertFalse(FinancialMessageParser.parse(
            "메신저",
            "새 메시지",
            "오늘 저녁에 만나요",
            NOW
        ).isPresent());
    }

    private static FinancialMessageParser.Result parsed(String sender, String title, String body) {
        Optional<FinancialMessageParser.Result> parsed =
            FinancialMessageParser.parse(sender, title, body, NOW);
        assertTrue(parsed.isPresent());
        return parsed.get();
    }
}
