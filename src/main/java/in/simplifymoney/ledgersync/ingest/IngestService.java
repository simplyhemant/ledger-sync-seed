package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class IngestService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        List<ParsedTxn> parsedList = new ArrayList<>();
        int skipped = 0;

        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            parsedList.add(p.get());
        }

        List<NormalizedTxn> canonicalTxns = buildCanonicalTransactions(parsedList);

        System.out.println("parsed transactions = " + parsedList.size());
        System.out.println("canonical transactions = " + canonicalTxns.size());

        Set<String> existingSignatures = new HashSet<>();
        for (NormalizedTxn existing : store.all()) {
            existingSignatures.add(signature(existing.accountLast4(), existing.occurredAt(),
                    existing.direction(), existing.amount()));
        }

        int written = 0;
        for (NormalizedTxn txn : canonicalTxns) {
            String sig = signature(txn.accountLast4(), txn.occurredAt(), txn.direction(), txn.amount());
            if (!existingSignatures.contains(sig)) {
                store.save(txn);
                existingSignatures.add(sig);
                written++;
            }
        }

        return new Stats(messages.size(), written, skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private List<NormalizedTxn> buildCanonicalTransactions(List<ParsedTxn> parsedList) {
        record TxnKey(String accountLast4, OffsetDateTime occurredAt, Direction direction,
                BigDecimal amount, String merchant) {
        }

        Map<TxnKey, Set<String>> grouped = new LinkedHashMap<>();
        for (ParsedTxn p : parsedList) {
            TxnKey key = new TxnKey(
                    p.accountLast4(),
                    p.occurredAt(),
                    p.direction(),
                    p.amount().setScale(2),
                    p.merchant().trim());
            grouped.computeIfAbsent(key, k -> new TreeSet<>()).add(p.sourceMessageId());
        }

        // DEBUG: show transactions that are being merged
        System.out.println("========================================");
        System.out.println("GROUPED SIZE = " + grouped.size());
        System.out.println("PARSED SIZE  = " + parsedList.size());

        for (Map.Entry<TxnKey, Set<String>> entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                System.out.println(
                        "MERGED: " + entry.getKey()
                                + " -> " + entry.getValue());
            }
        }

        System.out.println("========================================");

        class Candidate {
            final String accountLast4;
            final OffsetDateTime occurredAt;
            final Direction direction;
            final BigDecimal amount;
            final String merchant;
            final List<String> sourceMessageIds;
            Category category;

            Candidate(TxnKey key, Set<String> ids) {
                this.accountLast4 = key.accountLast4();
                this.occurredAt = key.occurredAt();
                this.direction = key.direction();
                this.amount = key.amount();
                this.merchant = key.merchant();
                this.sourceMessageIds = new ArrayList<>(ids);
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<TxnKey, Set<String>> entry : grouped.entrySet()) {
            candidates.add(new Candidate(entry.getKey(), entry.getValue()));
        }

        candidates.sort(Comparator.comparing(c -> c.occurredAt));

        boolean[] matchedTransfer = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c1 = candidates.get(i);
            if (matchedTransfer[i] || c1.direction != Direction.DEBIT)
                continue;
            for (int j = 0; j < candidates.size(); j++) {
                if (i == j || matchedTransfer[j])
                    continue;
                Candidate c2 = candidates.get(j);
                if (c2.direction != Direction.CREDIT)
                    continue;
                if (c1.accountLast4.equals(c2.accountLast4))
                    continue;
                if (c1.amount.compareTo(c2.amount) != 0)
                    continue;
                long diffMinutes = Math.abs(Duration.between(c1.occurredAt, c2.occurredAt).toMinutes());
                if (diffMinutes <= 10 && isTransferMerchant(c1.merchant, c2.merchant)) {
                    c1.category = Category.TRANSFER;
                    c2.category = Category.TRANSFER;
                    matchedTransfer[i] = true;
                    matchedTransfer[j] = true;
                    break;
                }
            }
        }

        List<NormalizedTxn> result = new ArrayList<>();
        for (Candidate c : candidates) {
            Category cat = c.category;
            if (cat == null) {
                if (c.direction == Direction.DEBIT) {
                    if (c.amount.compareTo(HUNDRED) <= 0 && isUpi(c.merchant)) {
                        cat = Category.MICRO;
                    } else {
                        cat = Category.SPEND;
                    }
                } else {
                    cat = Category.INCOME;
                }
            }
            result.add(new NormalizedTxn(c.accountLast4, c.occurredAt, c.direction,
                    c.amount, cat, c.merchant, c.sourceMessageIds));
        }

        result.sort(Comparator.comparing(NormalizedTxn::occurredAt));
        return result;
    }

    private static boolean isTransferMerchant(String m1, String m2) {
        String u1 = m1.toUpperCase();
        String u2 = m2.toUpperCase();
        return u1.contains("PARAG KAPOOR") && u2.contains("PARAG KAPOOR");
    }

    private static boolean isUpi(String merchant) {
        String upper = merchant.toUpperCase();
        return upper.startsWith("UPI/") || upper.startsWith("UPI ");
    }

    private static String signature(String acct, OffsetDateTime at, Direction d, BigDecimal amount) {
        return acct + "|" + at.toString() + "|" + d.name() + "|" + amount.setScale(2).toPlainString();
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {
    }
}
