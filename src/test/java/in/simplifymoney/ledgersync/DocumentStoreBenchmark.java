package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Dates;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Benchmark to generate 100,000 realistic canonical transactions in MongoDB,
 * execute the three required query patterns under executionStats, and report
 * totalDocsExamined vs nReturned.
 */
public final class DocumentStoreBenchmark {

    private static final String BENCH_DB = "ledger_sync_bench";
    private static final String BENCH_COLL = "transactions";

    public static void main(String[] args) throws Exception {
        System.out.println("=========================================================");
        System.out.println("Document Store Query Efficiency Benchmark (100,000 Txns)");
        System.out.println("=========================================================");

        String uri = System.getenv("MONGO_URI");
        if (uri == null || uri.isBlank()) {
            uri = "mongodb://localhost:27017";
        }

        try (MongoDocumentStore store = new MongoDocumentStore(uri, BENCH_DB, BENCH_COLL)) {
            System.out.println("Connected to MongoDB at " + uri);
            System.out.println("Preparing collection and indexes...");
            store.dropCollection();

            System.out.println("Seeding 100,000 realistic transactions in batches...");
            seed100kTransactions(uri, BENCH_DB, BENCH_COLL);
            System.out.printf("Seeded successfully. Current count: %d%n%n", store.count());

            String acct = "4821";
            YearMonth month = YearMonth.of(2026, 7);
            MongoDocumentStore.ExecutionStats q1Stats = store.explainForAccountMonth(acct, month);

            MongoDocumentStore.ExecutionStats q2Stats = store.explainCategoryTotals(acct);

            String targetMsgId = "m-bench-048210";
            MongoDocumentStore.ExecutionStats q3Stats = store.explainByMessageId(targetMsgId);

            System.out.println("----------------------------------------------------------------------------------");
            System.out.printf("%-40s | %-18s | %-16s%n", "Query Pattern", "totalDocsExamined", "nReturned");
            System.out.println("----------------------------------------------------------------------------------");
            System.out.printf("%-40s | %-18d | %-16d%n",
                    "Q1: forAccountMonth (4821, 2026-07)", q1Stats.totalDocsExamined(), q1Stats.nReturned());
            System.out.printf("%-40s | %-18d | %-16d%n",
                    "Q2: categoryTotals (4821, history)", q2Stats.totalDocsExamined(), q2Stats.nReturned());
            System.out.printf("%-40s | %-18d | %-16d%n",
                    "Q3: byMessageId (" + targetMsgId + ")", q3Stats.totalDocsExamined(), q3Stats.nReturned());
            System.out.println("----------------------------------------------------------------------------------");
            System.out.println("Benchmark completed successfully.");
        } catch (Exception e) {
            System.err.println("Could not run benchmark against MongoDB: " + e.getMessage());
            System.err.println("Ensure MongoDB is running (docker compose up -d) before executing benchmark.");
        }
    }

    private static void seed100kTransactions(String uri, String dbName, String collName) throws Exception {
        Class<?> mongoClientsClass = Class.forName("com.mongodb.client.MongoClients");
        Object client = mongoClientsClass.getMethod("create", String.class).invoke(null, uri);
        Class<?> mongoClientClass = Class.forName("com.mongodb.client.MongoClient");
        Object db = mongoClientClass.getMethod("getDatabase", String.class).invoke(client, dbName);
        Class<?> docClass = Class.forName("org.bson.Document");
        Constructor<?> docMapCons = docClass.getConstructor(Map.class);
        Class<?> mongoDbClass = Class.forName("com.mongodb.client.MongoDatabase");
        Object coll = mongoDbClass.getMethod("getCollection", String.class, Class.class).invoke(db, collName, docClass);
        Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
        Method insertManyMethod = mongoCollClass.getMethod("insertMany", List.class);

        String[] accounts = {"4821", "9075", "1122", "3344", "5566"};
        Category[] categories = Category.values();
        String[] merchants = {"AMAZON", "SWIGGY", "ZOMATO", "FLIPKART", "BLINKIT", "BIGBASKET", "SALARY CREDIT"};

        int total = 100_000;
        int batchSize = 5_000;
        List<Object> batch = new ArrayList<>(batchSize);

        for (int i = 1; i <= total; i++) {
            String acct = accounts[i % accounts.length];
            int monthVal = 1 + ((i / 500) % 12);
            int dayVal = 1 + (i % 28);
            int hourVal = (i % 24);
            int minVal = (i % 60);
            OffsetDateTime time = OffsetDateTime.of(2026, monthVal, dayVal, hourVal, minVal, 0, 0, Dates.IST);

            Direction dir = (i % 7 == 0) ? Direction.CREDIT : Direction.DEBIT;
            Category cat = (dir == Direction.CREDIT) ? Category.INCOME : categories[i % categories.length];
            BigDecimal amount = new BigDecimal(String.format("%.2f", 10.0 + (i % 5000) * 0.50));
            String merchant = merchants[i % merchants.length];
            String msgId = String.format("m-bench-%06d", i);

            NormalizedTxn txn = new NormalizedTxn(acct, time, dir, amount, cat, merchant, List.of(msgId));
            String id = InMemoryDocumentStore.canonicalId(txn);

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_id", id);
            doc.put("account_last4", acct);
            doc.put("occurred_at", time.toString());
            doc.put("occurred_epoch_ms", time.toInstant().toEpochMilli());
            doc.put("direction", dir.name());
            doc.put("amount", amount.toPlainString());
            doc.put("category", cat.name());
            doc.put("merchant", merchant);
            doc.put("source_message_ids", List.of(msgId));

            batch.add(docMapCons.newInstance(doc));

            if (batch.size() == batchSize) {
                insertManyMethod.invoke(coll, batch);
                batch.clear();
                System.out.printf("  Inserted %d / %d records...%n", i, total);
            }
        }

        if (!batch.isEmpty()) {
            insertManyMethod.invoke(coll, batch);
            batch.clear();
        }

        client.getClass().getMethod("close").invoke(client);
    }
}
