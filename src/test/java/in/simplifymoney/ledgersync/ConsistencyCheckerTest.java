package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsistencyCheckerTest {

    @TempDir
    Path tempDir;

    private SqlLedgerStore sqlStore;
    private InMemoryDocumentStore docStore;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_checker_ledger");
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
    @DisplayName("consistent stores produce zero divergences")
    void consistentStoresProduceZeroDivergences() {
        Backfill backfill = new Backfill(sqlStore, docStore);
        backfill.run();

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertTrue(divergences.isEmpty(), "Consistent stores should have 0 divergences but got: " + divergences);
    }

    @Test
    @DisplayName("detects transaction missing from DocumentStore")
    void detectsMissingFromDocumentStore() {
        Backfill backfill = new Backfill(sqlStore, docStore);
        backfill.run();

        NormalizedTxn extraSql = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-20T10:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("123.45"), Category.SPEND, "STORE X", List.of("m-new-1"));
        sqlStore.save(extraSql);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertFalse(divergences.isEmpty());
        assertTrue(divergences.stream().anyMatch(d -> d.what().startsWith("missing_in_document_store")));
    }

    @Test
    @DisplayName("detects extra transaction present only in DocumentStore")
    void detectsExtraInDocumentStore() {
        Backfill backfill = new Backfill(sqlStore, docStore);
        backfill.run();

        NormalizedTxn extraDoc = new NormalizedTxn("4821",
                OffsetDateTime.parse("2026-07-22T15:00:00+05:30"),
                Direction.CREDIT, new BigDecimal("500.00"), Category.INCOME, "BONUS", List.of("m-extra-1"));
        docStore.save(extraDoc);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertFalse(divergences.isEmpty());
        assertTrue(divergences.stream().anyMatch(d -> d.what().startsWith("extra_in_document_store")));
    }

    @Test
    @DisplayName("detects amount modification in DocumentStore")
    void detectsAmountModification() {
        OffsetDateTime time = OffsetDateTime.parse("2026-07-10T10:00:00+05:30");
        NormalizedTxn sqlTxn = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("2499.50"), Category.SPEND, "AMAZON", List.of("m-mod-1"));
        sqlStore.save(sqlTxn);

        NormalizedTxn alteredDoc = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("2999.50"), Category.SPEND, "AMAZON", List.of("m-mod-1"));
        docStore.save(alteredDoc);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertTrue(divergences.stream().anyMatch(d -> d.what().contains("amount mismatch")
                && d.inSql().equals("2499.50") && d.inDocuments().equals("2999.50")));
    }

    @Test
    @DisplayName("detects category modification in DocumentStore")
    void detectsCategoryModification() {
        OffsetDateTime time = OffsetDateTime.parse("2026-07-10T11:00:00+05:30");
        NormalizedTxn sqlTxn = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("50.00"), Category.SPEND, "CHAI", List.of("m-cat-1"));
        sqlStore.save(sqlTxn);

        NormalizedTxn alteredDoc = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("50.00"), Category.MICRO, "CHAI", List.of("m-cat-1"));
        docStore.save(alteredDoc);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertTrue(divergences.stream().anyMatch(d -> d.what().contains("category mismatch")
                && d.inSql().equals("SPEND") && d.inDocuments().equals("MICRO")));
    }

    @Test
    @DisplayName("detects merchant modification in DocumentStore")
    void detectsMerchantModification() {
        OffsetDateTime time = OffsetDateTime.parse("2026-07-10T12:00:00+05:30");
        NormalizedTxn sqlTxn = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("100.00"), Category.SPEND, "SWIGGY", List.of("m-merch-1"));
        sqlStore.save(sqlTxn);

        NormalizedTxn alteredDoc = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("100.00"), Category.SPEND, "ZOMATO", List.of("m-merch-1"));
        docStore.save(alteredDoc);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertTrue(divergences.stream().anyMatch(d -> d.what().contains("merchant mismatch")
                && d.inSql().equals("SWIGGY") && d.inDocuments().equals("ZOMATO")));
    }

    @Test
    @DisplayName("detects source message ID mismatch")
    void detectsSourceMessageIdsMismatch() {
        OffsetDateTime time = OffsetDateTime.parse("2026-07-10T13:00:00+05:30");
        NormalizedTxn sqlTxn = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("100.00"), Category.SPEND, "STORE", List.of("m-source-1"));
        sqlStore.save(sqlTxn);

        NormalizedTxn alteredDoc = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("100.00"), Category.SPEND, "STORE", List.of("m-source-2"));
        docStore.save(alteredDoc);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertTrue(divergences.stream().anyMatch(d -> d.what().contains("source_message_ids mismatch")));
    }

    @Test
    @DisplayName("source message IDs in different order do not create false divergence")
    void sourceMessageIdsOrderInsensitive() {
        OffsetDateTime time = OffsetDateTime.parse("2026-07-10T14:00:00+05:30");
        NormalizedTxn sqlTxn = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("100.00"), Category.SPEND, "STORE", List.of("m-order-1", "m-order-2"));
        sqlStore.save(sqlTxn);

        NormalizedTxn docTxn = new NormalizedTxn("4821", time, Direction.DEBIT,
                new BigDecimal("100.00"), Category.SPEND, "STORE", List.of("m-order-2", "m-order-1"));
        docStore.save(docTxn);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertTrue(divergences.isEmpty(), "Order differences in source message IDs should not cause divergence");
    }
}
