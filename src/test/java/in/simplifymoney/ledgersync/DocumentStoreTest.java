package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.DocumentStore;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentStoreTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private DocumentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDocumentStore();
    }

    private static NormalizedTxn createTxn(String acct, String isoTime, Direction dir,
                                           String amount, Category cat, String merchant,
                                           List<String> msgIds) {
        return new NormalizedTxn(
                acct,
                OffsetDateTime.parse(isoTime),
                dir,
                new BigDecimal(amount).setScale(2),
                cat,
                merchant,
                msgIds);
    }

    @Test
    @DisplayName("save and retrieve transaction by message ID")
    void saveAndRetrieveByMessageId() {
        NormalizedTxn txn = createTxn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "AMAZON PAY", List.of("m-1", "m-2"));
        store.save(txn);

        Optional<NormalizedTxn> fromM1 = store.byMessageId("m-1");
        assertTrue(fromM1.isPresent());
        assertEquals("4821", fromM1.get().accountLast4());
        assertEquals(new BigDecimal("2499.50"), fromM1.get().amount());
        assertEquals("AMAZON PAY", fromM1.get().merchant());

        Optional<NormalizedTxn> fromM2 = store.byMessageId("m-2");
        assertTrue(fromM2.isPresent());
        assertEquals(fromM1.get(), fromM2.get());

        assertTrue(store.byMessageId("m-non-existent").isEmpty());
    }

    @Test
    @DisplayName("forAccountMonth returns transactions newest first")
    void forAccountMonthNewestFirst() {
        NormalizedTxn t1 = createTxn("4821", "2026-07-02T10:00:00+05:30", Direction.DEBIT,
                "100.00", Category.SPEND, "STORE A", List.of("m-10"));
        NormalizedTxn t2 = createTxn("4821", "2026-07-15T18:30:00+05:30", Direction.DEBIT,
                "250.00", Category.SPEND, "STORE B", List.of("m-11"));
        NormalizedTxn t3 = createTxn("4821", "2026-07-08T12:15:00+05:30", Direction.CREDIT,
                "500.00", Category.INCOME, "SALARY", List.of("m-12"));
        NormalizedTxn june = createTxn("4821", "2026-06-30T23:59:00+05:30", Direction.DEBIT,
                "50.00", Category.MICRO, "CHAI", List.of("m-13"));
        NormalizedTxn otherAcct = createTxn("9075", "2026-07-10T12:00:00+05:30", Direction.DEBIT,
                "75.00", Category.SPEND, "CAB", List.of("m-14"));

        store.save(t1);
        store.save(t2);
        store.save(t3);
        store.save(june);
        store.save(otherAcct);

        List<NormalizedTxn> july4821 = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(3, july4821.size());
        assertEquals(t2.occurredAt(), july4821.get(0).occurredAt());
        assertEquals(t3.occurredAt(), july4821.get(1).occurredAt());
        assertEquals(t1.occurredAt(), july4821.get(2).occurredAt());
    }

    @Test
    @DisplayName("categoryTotals calculates running totals per category for account")
    void categoryTotalsCalculatesPerAccount() {
        NormalizedTxn t1 = createTxn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                "1500.00", Category.SPEND, "AMAZON", List.of("m-20"));
        NormalizedTxn t2 = createTxn("4821", "2026-07-02T10:00:00+05:30", Direction.DEBIT,
                "500.50", Category.SPEND, "FLIPKART", List.of("m-21"));
        NormalizedTxn t3 = createTxn("4821", "2026-07-03T10:00:00+05:30", Direction.CREDIT,
                "10000.00", Category.INCOME, "SALARY", List.of("m-22"));
        NormalizedTxn t4 = createTxn("4821", "2026-07-04T10:00:00+05:30", Direction.DEBIT,
                "40.00", Category.MICRO, "TEA", List.of("m-23"));
        NormalizedTxn other = createTxn("9075", "2026-07-01T10:00:00+05:30", Direction.DEBIT,
                "9999.00", Category.SPEND, "EXPENSIVE", List.of("m-24"));

        store.save(t1);
        store.save(t2);
        store.save(t3);
        store.save(t4);
        store.save(other);

        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("2000.50"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("10000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("40.00"), totals.get(Category.MICRO));
        assertEquals(new BigDecimal("0.00"), totals.get(Category.TRANSFER));
    }

    @Test
    @DisplayName("save merges evidence messages for duplicate canonical transaction")
    void saveMergesEvidenceMessages() {
        NormalizedTxn msgA = createTxn("4821", "2026-07-04T11:00:00+05:30", Direction.DEBIT,
                "449.00", Category.SPEND, "SWIGGY", List.of("m-sms-1"));
        NormalizedTxn msgB = createTxn("4821", "2026-07-04T11:00:00+05:30", Direction.DEBIT,
                "449.00", Category.SPEND, "SWIGGY", List.of("m-email-2"));

        store.save(msgA);
        store.save(msgB);

        Optional<NormalizedTxn> txn = store.byMessageId("m-sms-1");
        assertTrue(txn.isPresent());
        assertEquals(List.of("m-email-2", "m-sms-1"), txn.get().sourceMessageIds());

        Optional<NormalizedTxn> sameTxn = store.byMessageId("m-email-2");
        assertTrue(sameTxn.isPresent());
        assertEquals(txn.get(), sameTxn.get());

        store.save(msgA);
        Optional<NormalizedTxn> unchanged = store.byMessageId("m-sms-1");
        assertEquals(List.of("m-email-2", "m-sms-1"), unchanged.get().sourceMessageIds());
    }
}
