package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry point.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 *   backfill                 migrate transactions from SQL to DocumentStore
 *   check                    verify consistency between SQL and DocumentStore
 */
public final class App {

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: migrate | ingest <corpus.jsonl> | report <out-dir> | backfill | check");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliation(ledger)));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB);
                     MongoDocumentStore docStore = new MongoDocumentStore()) {
                    var result = new in.simplifymoney.ledgersync.store.Backfill(store, docStore).run();
                    System.out.printf("Backfill complete: read=%d, written=%d, skipped=%d, totalInStore=%d%n",
                            result.read(), result.written(), result.skipped(), docStore.count());
                }
            }
            case "check" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB);
                     MongoDocumentStore docStore = new MongoDocumentStore()) {
                    var divergences = new in.simplifymoney.ledgersync.store.ConsistencyChecker(store, docStore).check();
                    if (divergences.isEmpty()) {
                        System.out.println("STORES ARE CONSISTENT: SQL and DocumentStore contain equivalent canonical transactions.");
                    } else {
                        System.out.printf("DIVERGENCES FOUND: %d%n", divergences.size());
                        for (var d : divergences) {
                            System.out.printf("  - %s | SQL: %s | DOC: %s%n", d.what(), d.inSql(), d.inDocuments());
                        }
                    }
                }
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }
}
