package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.lang.reflect.Method;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        Map<String, NormalizedTxn> sqlCanonical = canonicalizeSql(sql.all());
        Map<String, NormalizedTxn> docCanonical = retrieveAllDocuments(sqlCanonical);

        List<Divergence> divergences = new ArrayList<>();

        for (Map.Entry<String, NormalizedTxn> entry : sqlCanonical.entrySet()) {
            String key = entry.getKey();
            NormalizedTxn sqlTxn = entry.getValue();
            NormalizedTxn docTxn = docCanonical.get(key);

            if (docTxn == null) {
                divergences.add(new Divergence(
                        "missing_in_document_store: " + key,
                        formatTxn(sqlTxn),
                        "missing"));
                continue;
            }

            if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
                divergences.add(new Divergence(
                        "amount mismatch on " + key,
                        sqlTxn.amount().toPlainString(),
                        docTxn.amount().toPlainString()));
            }

            if (sqlTxn.category() != docTxn.category()) {
                divergences.add(new Divergence(
                        "category mismatch on " + key,
                        sqlTxn.category().name(),
                        docTxn.category().name()));
            }

            if (!sqlTxn.merchant().trim().equalsIgnoreCase(docTxn.merchant().trim())) {
                divergences.add(new Divergence(
                        "merchant mismatch on " + key,
                        sqlTxn.merchant(),
                        docTxn.merchant()));
            }

            if (sqlTxn.direction() != docTxn.direction()) {
                divergences.add(new Divergence(
                        "direction mismatch on " + key,
                        sqlTxn.direction().name(),
                        docTxn.direction().name()));
            }

            if (!sqlTxn.occurredAt().toInstant().equals(docTxn.occurredAt().toInstant())) {
                divergences.add(new Divergence(
                        "occurred_at mismatch on " + key,
                        sqlTxn.occurredAt().toString(),
                        docTxn.occurredAt().toString()));
            }

            Set<String> sqlMsgs = new HashSet<>(sqlTxn.sourceMessageIds());
            Set<String> docMsgs = new HashSet<>(docTxn.sourceMessageIds());
            if (!sqlMsgs.equals(docMsgs)) {
                divergences.add(new Divergence(
                        "source_message_ids mismatch on " + key,
                        sqlTxn.sourceMessageIds().toString(),
                        docTxn.sourceMessageIds().toString()));
            }
        }

        for (Map.Entry<String, NormalizedTxn> entry : docCanonical.entrySet()) {
            String key = entry.getKey();
            if (!sqlCanonical.containsKey(key)) {
                divergences.add(new Divergence(
                        "extra_in_document_store: " + key,
                        "missing",
                        formatTxn(entry.getValue())));
            }
        }

        return Collections.unmodifiableList(divergences);
    }

    private static Map<String, NormalizedTxn> canonicalizeSql(List<NormalizedTxn> rows) {
        Map<String, NormalizedTxn> canonical = new LinkedHashMap<>();
        Map<String, Set<String>> evidenceMap = new HashMap<>();

        for (NormalizedTxn r : rows) {
            String id = InMemoryDocumentStore.canonicalId(r);
            evidenceMap.computeIfAbsent(id, k -> new TreeSet<>()).addAll(r.sourceMessageIds());
            canonical.putIfAbsent(id, r);
        }

        Map<String, NormalizedTxn> result = new LinkedHashMap<>();
        for (Map.Entry<String, NormalizedTxn> e : canonical.entrySet()) {
            NormalizedTxn base = e.getValue();
            Set<String> allEvidence = evidenceMap.get(e.getKey());
            result.put(e.getKey(), new NormalizedTxn(
                    base.accountLast4(),
                    base.occurredAt(),
                    base.direction(),
                    base.amount(),
                    base.category(),
                    base.merchant(),
                    new ArrayList<>(allEvidence)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, NormalizedTxn> retrieveAllDocuments(Map<String, NormalizedTxn> sqlTxns) {
        Map<String, NormalizedTxn> docs = new LinkedHashMap<>();

        try {
            Method allMethod = documents.getClass().getMethod("all");
            List<NormalizedTxn> list = (List<NormalizedTxn>) allMethod.invoke(documents);
            for (NormalizedTxn t : list) {
                docs.put(InMemoryDocumentStore.canonicalId(t), t);
            }
            return docs;
        } catch (Exception ignored) {
        }

        Set<String> visited = new HashSet<>();
        for (NormalizedTxn t : sqlTxns.values()) {
            String partition = t.accountLast4() + ":" + YearMonth.from(t.occurredAt());
            if (visited.add(partition)) {
                List<NormalizedTxn> monthTxns = documents.forAccountMonth(
                        t.accountLast4(), YearMonth.from(t.occurredAt()));
                for (NormalizedTxn mt : monthTxns) {
                    docs.put(InMemoryDocumentStore.canonicalId(mt), mt);
                }
            }
        }
        return docs;
    }

    private static String formatTxn(NormalizedTxn t) {
        return String.format("account=%s, occurred_at=%s, dir=%s, amount=%s, cat=%s, merchant='%s', msgs=%s",
                t.accountLast4(), t.occurredAt(), t.direction(),
                t.amount().toPlainString(), t.category(), t.merchant(), t.sourceMessageIds());
    }

    public record Divergence(String what, String inSql, String inDocuments) {}
}
