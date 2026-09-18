package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        return runWithLimit(Integer.MAX_VALUE);
    }

    public Result runWithLimit(int maxRowsToProcess) {
        List<NormalizedTxn> sqlTxns = source.all();

        long read = 0;
        long written = 0;
        long skipped = 0;

        Map<String, Map<String, Set<String>>> cache = new HashMap<>();

        for (NormalizedTxn t : sqlTxns) {
            if (read >= maxRowsToProcess) {
                break;
            }
            read++;

            String partitionKey = t.accountLast4() + ":" + YearMonth.from(t.occurredAt());
            Map<String, Set<String>> monthMap = cache.computeIfAbsent(partitionKey, k -> {
                Map<String, Set<String>> m = new HashMap<>();
                List<NormalizedTxn> existingInTarget = target.forAccountMonth(
                        t.accountLast4(), YearMonth.from(t.occurredAt()));
                for (NormalizedTxn existing : existingInTarget) {
                    m.put(InMemoryDocumentStore.canonicalId(existing),
                            new HashSet<>(existing.sourceMessageIds()));
                }
                return m;
            });

            String canonId = InMemoryDocumentStore.canonicalId(t);
            Set<String> knownIds = monthMap.get(canonId);

            if (knownIds != null && knownIds.containsAll(t.sourceMessageIds())) {
                skipped++;
            } else {
                target.save(t);
                written++;
                if (knownIds == null) {
                    knownIds = new HashSet<>();
                    monthMap.put(canonId, knownIds);
                }
                knownIds.addAll(t.sourceMessageIds());
            }
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
