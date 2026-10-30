package softm.android.gagyebu;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReceiptTextParser {
    private static final Pattern AMOUNT = Pattern.compile("(?<![0-9])(\\d{1,3}(?:[,\\s]\\d{3})+|\\d{2,12})\\s*(?:원|₩)?");
    private static final Pattern FULL_DATE = Pattern.compile("(20\\d{2})[./-]\\s*(\\d{1,2})[./-]\\s*(\\d{1,2})");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[./-]\\s*(\\d{1,2})(?!\\d)");
    private static final Pattern TOTAL = Pattern.compile("총액|합계|결제금액|승인금액|받을금액|청구금액|카드금액|TOTAL", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXCLUDE_AMOUNT = Pattern.compile("부가세|과세|면세|공급가|거스름|할인|포인트|현금영수증|사업자|전화|TEL", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERCHANT_NOISE = Pattern.compile("영수증|매출전표|신용카드|체크카드|카드결제|승인번호|사업자|대표자|주소|전화|TEL|합계|총액|결제금액|금액", Pattern.CASE_INSENSITIVE);

    private ReceiptTextParser() {}

    static Result parse(String rawText, long now) {
        String text = rawText == null ? "" : rawText.replace('\u0000', ' ').trim();
        String[] lines = text.split("\\R+");
        long amount = selectAmount(lines);
        String date = selectDate(text, now);
        String merchant = selectMerchant(lines);
        return new Result(amount, date, merchant);
    }

    private static long selectAmount(String[] lines) {
        List<AmountCandidate> candidates = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String line = lines[lineIndex].trim();
            Matcher matcher = AMOUNT.matcher(line);
            while (matcher.find()) {
                String digits = matcher.group(1).replaceAll("[,\\s]", "");
                long value;
                try {
                    value = Long.parseLong(digits);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (value <= 0 || value > 999_999_999_999L) continue;
                int score = 0;
                if (TOTAL.matcher(line).find()) score += 12;
                if (EXCLUDE_AMOUNT.matcher(line).find()) score -= 10;
                if (matcher.group().contains(",")) score += 2;
                if (matcher.group().contains("원") || matcher.group().contains("₩")) score += 2;
                score += Math.min(lineIndex, 20) / 5;
                candidates.add(new AmountCandidate(value, score));
            }
        }
        AmountCandidate best = null;
        for (AmountCandidate candidate : candidates) {
            if (best == null || candidate.score > best.score ||
                (candidate.score == best.score && candidate.value > best.value)) {
                best = candidate;
            }
        }
        return best == null ? 0 : best.value;
    }

    private static String selectDate(String text, long now) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        Matcher full = FULL_DATE.matcher(text);
        if (full.find()) {
            int year = integer(full.group(1));
            int month = integer(full.group(2));
            int day = integer(full.group(3));
            if (validDate(year, month, day)) return format(year, month, day);
        }
        Matcher shortDate = SHORT_DATE.matcher(text);
        if (shortDate.find()) {
            int month = integer(shortDate.group(1));
            int day = integer(shortDate.group(2));
            int year = calendar.get(Calendar.YEAR);
            if (validDate(year, month, day)) return format(year, month, day);
        }
        return "";
    }

    private static String selectMerchant(String[] lines) {
        for (String value : lines) {
            String line = value.replaceAll("\\s+", " ").trim();
            if (line.length() < 2 || line.length() > 60) continue;
            if (AMOUNT.matcher(line).find() || FULL_DATE.matcher(line).find() || SHORT_DATE.matcher(line).find()) continue;
            if (MERCHANT_NOISE.matcher(line).find()) continue;
            if (line.matches(".*\\d{4,}.*")) continue;
            return line.substring(0, Math.min(line.length(), 90));
        }
        return "";
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean validDate(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance();
        calendar.setLenient(false);
        calendar.clear();
        calendar.set(year, month - 1, day);
        try {
            calendar.getTime();
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String format(int year, int month, int day) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }

    static final class Result {
        final long amount;
        final String date;
        final String merchant;

        Result(long amount, String date, String merchant) {
            this.amount = amount;
            this.date = date;
            this.merchant = merchant;
        }
    }

    private static final class AmountCandidate {
        final long value;
        final int score;

        AmountCandidate(long value, int score) {
            this.value = value;
            this.score = score;
        }
    }
}
