package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackfillTest {

    @TempDir
    Path tempDir;

    private SqlLedgerStore sqlStore;
    private InMemoryDocumentStore docStore;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("test_ledger");
        sqlStore = new SqlLedgerStore(dbPath);
        sqlStore.migrate(Path.of("db", "migration"));
        docStore = new InMemoryDocumentStore();
    }

    @AfterEach
    void tearDown() {
        if (sqlStore != null) {
            sqlStore.close();
        }
    }

    @Test
    @DisplayName("initial backfill migrates 15 legacy SQL rows into 10 canonical transactions")
    void initialBackfillMigratesCanonicalTransactions() {
        Backfill backfill = new Backfill(sqlStore, docStore);
        Backfill.Result res = backfill.run();

        assertEquals(15, res.read(), "All 15 SQL rows should be read");
        assertEquals(10, docStore.count(), "Should produce exactly 10 canonical documents");

        var swiggyTxn = docStore.byMessageId("m-legacy-0001");
        assertTrue(swiggyTxn.isPresent());
        assertEquals(List.of("m-legacy-0001", "m-legacy-0002"), swiggyTxn.get().sourceMessageIds());
    }

    @Test
    @DisplayName("repeated backfill is idempotent and skips all existing rows")
    void repeatedBackfillIsIdempotent() {
        Backfill backfill = new Backfill(sqlStore, docStore);
        Backfill.Result firstRun = backfill.run();
        assertEquals(10, docStore.count());

        Backfill.Result secondRun = backfill.run();
        assertEquals(15, secondRun.read());
        assertEquals(0, secondRun.written(), "No new rows should be written on second run");
        assertEquals(15, secondRun.skipped(), "All 15 rows should be skipped");
        assertEquals(10, docStore.count(), "Document count should remain exactly 10");
    }

    @Test
    @DisplayName("backfill survives partial failure and finishes cleanly on resume")
    void survivesPartialFailureAndRecovers() {
        Backfill backfill = new Backfill(sqlStore, docStore);

        Backfill.Result partialRun = backfill.runWithLimit(6);
        assertEquals(6, partialRun.read());
        long docsAfterCrash = docStore.count();
        assertTrue(docsAfterCrash > 0 && docsAfterCrash < 10);

        Backfill.Result resumeRun = backfill.run();
        assertEquals(15, resumeRun.read());
        assertEquals(10, docStore.count(), "Final state must have all 10 canonical documents");
    }

    @Test
    @DisplayName("legitimate distinct transactions with same amount and merchant are not merged")
    void distinctTransactionsNotMerged() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");
        OffsetDateTime t2 = OffsetDateTime.parse("2026-07-01T12:00:00+05:30");

        NormalizedTxn txnA = new NormalizedTxn("4821", t1, Direction.DEBIT,
                new BigDecimal("50.00"), Category.SPEND, "STARBUCKS", List.of("m-coffee-1"));
        NormalizedTxn txnB = new NormalizedTxn("4821", t2, Direction.DEBIT,
                new BigDecimal("50.00"), Category.SPEND, "STARBUCKS", List.of("m-coffee-2"));

        sqlStore.save(txnA);
        sqlStore.save(txnB);

        Backfill backfill = new Backfill(sqlStore, docStore);
        backfill.run();

        var resA = docStore.byMessageId("m-coffee-1");
        var resB = docStore.byMessageId("m-coffee-2");

        assertTrue(resA.isPresent());
        assertTrue(resB.isPresent());
        assertEquals(t1, resA.get().occurredAt());
        assertEquals(t2, resB.get().occurredAt());
        assertEquals(List.of("m-coffee-1"), resA.get().sourceMessageIds());
        assertEquals(List.of("m-coffee-2"), resB.get().sourceMessageIds());
    }
}
