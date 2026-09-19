package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CorpusATest {

    @Test
    @SuppressWarnings("unchecked")
    void testCorpusAEndToEnd() throws Exception {
        Path corpus = Path.of("fixtures", "corpus-a.jsonl");
        Path totals = Path.of("fixtures", "corpus-a-totals.json");

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(corpus);

        assertEquals(522, stats.messagesRead());
        // assertEquals(257, stats.transactionsWritten());

        System.out.println("messagesRead = " + stats.messagesRead());
        System.out.println("transactionsWritten = " + stats.transactionsWritten());
        System.out.println("messagesSkipped = " + stats.messagesSkipped());

        assertEquals(
                257,
                stats.transactionsWritten(),
                "Actual transactionsWritten = " + stats.transactionsWritten());

        List<NormalizedTxn> ledger = store.all();
        assertEquals(257, ledger.size());

        IngestService.Stats rerunStats = ingest.ingestFile(corpus);
        assertEquals(0, rerunStats.transactionsWritten());
        assertEquals(257, store.all().size());

        Map<String, Object> want = Json.parseObject(Files.readString(totals));
        Map<String, Object> accounts = (Map<String, Object>) want.get("accounts");

        Map<String, Object> summary = Reports.summary(ledger);
        Map<String, Object> summaryAccounts = (Map<String, Object>) summary.get("accounts");
        assertNotNull(summaryAccounts);

        for (Map.Entry<String, Object> e : accounts.entrySet()) {
            String acct = e.getKey();
            Map<String, Object> wantAcct = (Map<String, Object>) e.getValue();
            Map<String, Object> gotAcct = (Map<String, Object>) summaryAccounts.get(acct);
            assertNotNull(gotAcct, "Account missing from summary: " + acct);

            assertEquals(wantAcct.get("spend"), gotAcct.get("spend"));
            assertEquals(wantAcct.get("income"), gotAcct.get("income"));
            assertEquals(wantAcct.get("micro_count"), gotAcct.get("micro_count"));
            assertEquals(wantAcct.get("micro_total"), gotAcct.get("micro_total"));
            assertEquals(wantAcct.get("transferred_out"), gotAcct.get("transferred_out"));
            assertEquals(wantAcct.get("transferred_in"), gotAcct.get("transferred_in"));

            BigDecimal opening = new BigDecimal((String) wantAcct.get("opening_balance"));
            BigDecimal closing = new BigDecimal((String) wantAcct.get("closing_balance"));

            BigDecimal running = opening;
            long count = 0;
            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct))
                    continue;
                count++;
                running = switch (t.direction()) {
                    case DEBIT -> running.subtract(t.amount());
                    case CREDIT -> running.add(t.amount());
                };
            }

            assertEquals(((Number) wantAcct.get("transactions_expected")).longValue(), count);
            assertEquals(0, running.compareTo(closing));
        }

        Map<String, Object> rec = Reports.reconciliation(ledger);
        List<?> discrepancies = (List<?>) rec.get("discrepancies");
        assertNotNull(discrepancies);
        assertEquals(0, discrepancies.size());

        Path outDir = Path.of("submission");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("ledger.json"), Json.writePretty(Reports.ledgerDocument(ledger)));
        Files.writeString(outDir.resolve("summary.json"), Json.writePretty(Reports.summary(ledger)));
        Files.writeString(outDir.resolve("reconciliation.json"), Json.writePretty(Reports.reconciliation(ledger)));
    }
}
