package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dependency-free, in-memory implementation of DocumentStore.
 *
 * Provides thread-safe storage, deterministic canonical transaction identity,
 * idempotent upserts, and support for all three required access patterns.
 * Compiles against pure JDK 21 with zero external dependencies.
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, NormalizedTxn> documents = new ConcurrentHashMap<>();
    private final Map<String, String> messageIndex = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String id = canonicalId(txn);
        NormalizedTxn existing = documents.get(id);

        if (existing == null) {
            documents.put(id, txn);
            for (String msgId : txn.sourceMessageIds()) {
                messageIndex.put(msgId, id);
            }
        } else {
            Set<String> mergedIds = new TreeSet<>(existing.sourceMessageIds());
            mergedIds.addAll(txn.sourceMessageIds());

            NormalizedTxn updated = new NormalizedTxn(
                    existing.accountLast4(),
                    existing.occurredAt(),
                    existing.direction(),
                    existing.amount(),
                    existing.category(),
                    existing.merchant(),
                    new ArrayList<>(mergedIds));

            documents.put(id, updated);
            for (String msgId : mergedIds) {
                messageIndex.put(msgId, id);
            }
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        List<NormalizedTxn> result = new ArrayList<>();
        for (NormalizedTxn t : documents.values()) {
            if (t.accountLast4().equals(accountLast4)
                    && t.occurredAt().getYear() == month.getYear()
                    && t.occurredAt().getMonth() == month.getMonth()) {
                result.add(t);
            }
        }
        result.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());
        return Collections.unmodifiableList(result);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            totals.put(c, BigDecimal.ZERO.setScale(2));
        }
        for (NormalizedTxn t : documents.values()) {
            if (t.accountLast4().equals(accountLast4)) {
                BigDecimal current = totals.get(t.category());
                totals.put(t.category(), current.add(t.amount()));
            }
        }
        return Collections.unmodifiableMap(totals);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        String txnId = messageIndex.get(messageId);
        if (txnId == null) {
            for (NormalizedTxn t : documents.values()) {
                if (t.sourceMessageIds().contains(messageId)) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
        return Optional.ofNullable(documents.get(txnId));
    }

    public List<NormalizedTxn> all() {
        return List.copyOf(documents.values());
    }

    public long count() {
        return documents.size();
    }

    public static String canonicalId(NormalizedTxn t) {
        String raw = t.accountLast4()
                + "|" + t.occurredAt().toInstant().toEpochMilli()
                + "|" + t.direction().name()
                + "|" + t.amount().stripTrailingZeros().toPlainString()
                + "|" + (t.merchant() == null ? "" : t.merchant().trim().toUpperCase());
        return "txn_" + sha256Hex(raw);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
