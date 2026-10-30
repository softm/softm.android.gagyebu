package softm.android.gagyebu;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FinancialMessageParser {
    private static final Pattern AMOUNT = Pattern.compile("(?<![0-9])(\\d{1,3}(?:,\\d{3})+|\\d{1,12})\\s*원");
    private static final Pattern FULL_DATE = Pattern.compile("(20\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)\\d{1,2}:\\d{2}(?!\\d)");
    private static final Pattern ACCOUNT_HINT = Pattern.compile("(?<![0-9*])(?=[0-9* -]{7,24}(?![0-9*]))(?=[0-9* -]*[*-])[0-9*]{2,4}(?:[- ]?[0-9*]{2,4}){1,4}");
    private static final Pattern RELEVANT = Pattern.compile("승인|결제|사용|출금|입금|이체|송금|취소|환불|체크카드|신용카드|카드");
    private static final Pattern AUTHENTICATION_MESSAGE = Pattern.compile(
        "OTP|인증번호|일회용\\s*비밀번호",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REFUND = Pattern.compile("승인취소|결제취소|취소|환불");
    private static final Pattern INCOME = Pattern.compile("입금|급여|월급|이체입금|송금받");
    private static final Pattern EXPENSE = Pattern.compile("승인|결제|사용|출금|이체출금|송금|자동이체");
    private static final Pattern LOW_VALUE_CONTEXT = Pattern.compile("잔액|누적|한도|포인트|혜택|할인|캐시백");
    private static final Pattern HIGH_VALUE_CONTEXT = Pattern.compile("승인|결제|사용|출금|입금|취소|환불|송금|이체");
    private static final Pattern NOISE = Pattern.compile(
        "\\[?web발신\\]?|\\[?광고\\]?|승인취소|결제취소|승인|결제|사용|출금|입금|이체|송금|취소|환불|체크카드|신용카드|카드|잔액|누적|한도",
        Pattern.CASE_INSENSITIVE
    );

    private FinancialMessageParser() {}

    public static Optional<Result> parse(String sender, String title, String body, long receivedAt) {
        String safeSender = cleanInput(sender);
        String safeTitle = cleanInput(title);
        String safeBody = cleanInput(body);
        String combined = String.join("\n", safeTitle, safeBody).trim();
        if (combined.isEmpty() || !RELEVANT.matcher(combined).find()) return Optional.empty();
        if (AUTHENTICATION_MESSAGE.matcher(combined).find()) return Optional.empty();
        if (combined.contains("[광고]") || combined.contains("(광고)")) return Optional.empty();

        AmountCandidate selected = selectAmount(combined);
        if (selected == null || selected.value <= 0 || selected.value > 999_999_999_999L) {
            return Optional.empty();
        }

        String kind;
        if (REFUND.matcher(combined).find()) kind = "refund";
        else if (INCOME.matcher(combined).find()) kind = "income";
        else if (EXPENSE.matcher(combined).find()) kind = "expense";
        else return Optional.empty();

        String accountHint = findAccountHint(combined);
        String accountType = combined.matches("(?s).*?(?:카드|승인|결제).*") ? "card" : "bank";
        String date = extractDate(combined, receivedAt);
        String merchant = extractMerchant(safeSender, safeTitle, safeBody, selected.raw);
        return Optional.of(new Result(kind, selected.value, date, merchant, accountHint, accountType));
    }

    private static AmountCandidate selectAmount(String text) {
        List<AmountCandidate> candidates = new ArrayList<>();
        Matcher matcher = AMOUNT.matcher(text);
        while (matcher.find()) {
            long value;
            try {
                value = Long.parseLong(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                continue;
            }
            int from = Math.max(0, matcher.start() - 18);
            int to = Math.min(text.length(), matcher.end() + 10);
            String context = text.substring(from, to);
            int score = 0;
            if (HIGH_VALUE_CONTEXT.matcher(context).find()) score += 6;
            if (LOW_VALUE_CONTEXT.matcher(context).find()) score -= 5;
            if (matcher.group(1).contains(",")) score += 1;
            candidates.add(new AmountCandidate(value, matcher.group(), score));
        }
        AmountCandidate best = null;
        for (AmountCandidate candidate : candidates) {
            if (best == null || candidate.score > best.score) best = candidate;
        }
        return best;
    }

    private static String extractDate(String text, long receivedAt) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(receivedAt);
        Matcher full = FULL_DATE.matcher(text);
        if (full.find()) {
            Integer year = number(full.group(1));
            Integer month = number(full.group(2));
            Integer day = number(full.group(3));
            if (validDate(year, month, day)) return formatDate(year, month, day);
        }
        Matcher shortDate = SHORT_DATE.matcher(text);
        if (shortDate.find()) {
            int year = calendar.get(Calendar.YEAR);
            Integer month = number(shortDate.group(1));
            Integer day = number(shortDate.group(2));
            if (validDate(year, month, day)) return formatDate(year, month, day);
        }
        return formatDate(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        );
    }

    private static boolean validDate(Integer year, Integer month, Integer day) {
        if (year == null || month == null || day == null) return false;
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

    private static String formatDate(int year, int month, int day) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }

    private static Integer number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String findAccountHint(String text) {
        Matcher matcher = ACCOUNT_HINT.matcher(text);
        return matcher.find() ? matcher.group().trim() : "";
    }

    private static String extractMerchant(String sender, String title, String body, String rawAmount) {
        String candidate = "";
        for (String line : (title + "\n" + body).split("\\n")) {
            if (line.contains(rawAmount)) {
                candidate = line;
                break;
            }
        }
        if (candidate.isBlank()) candidate = title;
        candidate = AMOUNT.matcher(candidate).replaceAll(" ");
        candidate = FULL_DATE.matcher(candidate).replaceAll(" ");
        candidate = SHORT_DATE.matcher(candidate).replaceAll(" ");
        candidate = TIME.matcher(candidate).replaceAll(" ");
        candidate = ACCOUNT_HINT.matcher(candidate).replaceAll(" ");
        candidate = NOISE.matcher(candidate).replaceAll(" ");
        candidate = candidate.replaceAll("[\\[\\](){},:;]", " ").replaceAll("\\s+", " ").trim();
        if (candidate.length() < 2) candidate = !title.isBlank() ? title : sender;
        candidate = candidate.replaceAll("\\s+", " ").trim();
        if (candidate.isBlank()) candidate = "자동 감지 거래";
        return candidate.substring(0, Math.min(candidate.length(), 90));
    }

    private static String cleanInput(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\u0000', ' ').trim();
        return cleaned.substring(0, Math.min(cleaned.length(), 4_000));
    }

    private static final class AmountCandidate {
        final long value;
        final String raw;
        final int score;

        AmountCandidate(long value, String raw, int score) {
            this.value = value;
            this.raw = raw;
            this.score = score;
        }
    }

    public static final class Result {
        public final String kind;
        public final long amount;
        public final String date;
        public final String merchant;
        public final String accountHint;
        public final String accountType;

        Result(String kind, long amount, String date, String merchant, String accountHint, String accountType) {
            this.kind = kind;
            this.amount = amount;
            this.date = date;
            this.merchant = merchant;
            this.accountHint = accountHint;
            this.accountType = accountType;
        }
    }
}

