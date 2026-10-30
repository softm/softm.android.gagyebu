package softm.android.gagyebu;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;

public final class ReceiptTextParserTest {
    private static final long NOW = Instant.parse("2026-10-30T03:00:00Z").toEpochMilli();

    @Test
    public void parsesKoreanReceiptTotalDateAndMerchant() {
        ReceiptTextParser.Result result = ReceiptTextParser.parse(
            "스타벅스 강남점\n2026-10-29 12:30\n공급가액 10,455원\n부가세 1,045원\n결제금액 11,500원",
            NOW
        );

        assertEquals(11_500L, result.amount);
        assertEquals("2026-10-29", result.date);
        assertEquals("스타벅스 강남점", result.merchant);
    }

    @Test
    public void prefersTotalAndUnderstandsShortDate() {
        ReceiptTextParser.Result result = ReceiptTextParser.parse(
            "행복마트\n10/28\n상품 8,000\n할인 1,000\n합계 7,000",
            NOW
        );

        assertEquals(7_000L, result.amount);
        assertEquals("2026-10-28", result.date);
        assertEquals("행복마트", result.merchant);
    }
}
