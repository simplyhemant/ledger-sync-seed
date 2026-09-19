package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmailParser implements MessageParser {

    private static final List<String> SENDERS = List.of(
            "alerts@hdfcbank.net",
            "alerts@icicibank.com");

    private static final Pattern DATE_HEADER = Pattern.compile("Date:\\s*([^\\r\\n]+)");
    private static final Pattern TXN_LINE = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with (?:INR|Rs\\.?)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern MERCHANT_LINE = Pattern.compile(
            "Merchant / Remarks:\\s*([^\\r\\n]+)");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH));

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel()) && SENDERS.contains(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher txnMatcher = TXN_LINE.matcher(body);
        if (!txnMatcher.find()) return Optional.empty();

        Matcher merchantMatcher = MERCHANT_LINE.matcher(body);
        if (!merchantMatcher.find()) return Optional.empty();

        Matcher dateMatcher = DATE_HEADER.matcher(body);
        if (!dateMatcher.find()) return Optional.empty();

        OffsetDateTime at = parseDate(dateMatcher.group(1).trim());
        if (at == null) return Optional.empty();

        BigDecimal amount = new BigDecimal(txnMatcher.group("amount").replace(",", "")).setScale(2);
        if (amount == null) return Optional.empty();

        String acct = txnMatcher.group("acct");
        Direction dir = "debited".equalsIgnoreCase(txnMatcher.group("dir"))
                ? Direction.DEBIT : Direction.CREDIT;
        String merchant = merchantMatcher.group(1).trim();

        return Optional.of(new ParsedTxn(acct, at, dir, amount, merchant, null, m.messageId()));
    }

    private OffsetDateTime parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return OffsetDateTime.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}
