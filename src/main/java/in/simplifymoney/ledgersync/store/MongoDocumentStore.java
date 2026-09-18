package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Dates;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    public static final String DEFAULT_URI = "mongodb://localhost:27017";
    public static final String DEFAULT_DATABASE = "ledger_sync";
    public static final String DEFAULT_COLLECTION = "transactions";

    private final Object client;
    private final Object database;
    private final Object collection;
    private final Class<?> docClass;
    private final Constructor<?> docMapCons;

    public MongoDocumentStore() {
        this(resolveUri(), DEFAULT_DATABASE, DEFAULT_COLLECTION);
    }

    public MongoDocumentStore(String uri, String dbName, String collName) {
        try {
            Class<?> mongoClientsClass = Class.forName("com.mongodb.client.MongoClients");
            Method createMethod = mongoClientsClass.getMethod("create", String.class);
            this.client = createMethod.invoke(null, uri);

            Class<?> mongoClientClass = Class.forName("com.mongodb.client.MongoClient");
            Method getDatabaseMethod = mongoClientClass.getMethod("getDatabase", String.class);
            this.database = getDatabaseMethod.invoke(this.client, dbName);

            this.docClass = Class.forName("org.bson.Document");
            this.docMapCons = this.docClass.getConstructor(Map.class);

            Class<?> mongoDbClass = Class.forName("com.mongodb.client.MongoDatabase");
            Method getCollectionMethod = mongoDbClass.getMethod("getCollection", String.class, Class.class);
            this.collection = getCollectionMethod.invoke(this.database, collName, this.docClass);

            ensureIndexes();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "MongoDB driver not found on runtime classpath. "
                            + "(Add 'org.mongodb:mongodb-driver-sync' to runtime classpath or run via Gradle/Docker)", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize MongoDocumentStore at " + uri, e);
        }
    }

    private static String resolveUri() {
        String env = System.getenv("MONGO_URI");
        return (env != null && !env.isBlank()) ? env : DEFAULT_URI;
    }

    private void ensureIndexes() {
        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> bsonClass = Class.forName("org.bson.conversions.Bson");
            Method createIndexMethod = mongoCollClass.getMethod("createIndex", bsonClass);

            Map<String, Object> idx1 = new LinkedHashMap<>();
            idx1.put("account_last4", 1);
            idx1.put("occurred_at", -1);
            createIndexMethod.invoke(this.collection, createDoc(idx1));

            Map<String, Object> idx2 = new LinkedHashMap<>();
            idx2.put("account_last4", 1);
            idx2.put("category", 1);
            idx2.put("amount", 1);
            createIndexMethod.invoke(this.collection, createDoc(idx2));

            Map<String, Object> idx3 = new LinkedHashMap<>();
            idx3.put("source_message_ids", 1);
            createIndexMethod.invoke(this.collection, createDoc(idx3));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create MongoDB indexes", e);
        }
    }

    private Object createDoc(Map<String, Object> map) {
        try {
            return docMapCons.newInstance(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate org.bson.Document", e);
        }
    }

    @Override
    public void save(NormalizedTxn txn) {
        Objects.requireNonNull(txn, "txn");
        String id = InMemoryDocumentStore.canonicalId(txn);

        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> bsonClass = Class.forName("org.bson.conversions.Bson");
            Class<?> updateOptionsClass = Class.forName("com.mongodb.client.model.UpdateOptions");
            Object options = updateOptionsClass.getConstructor().newInstance();
            updateOptionsClass.getMethod("upsert", boolean.class).invoke(options, true);

            Map<String, Object> filter = Map.of("_id", id);

            Map<String, Object> setOnInsert = new LinkedHashMap<>();
            setOnInsert.put("account_last4", txn.accountLast4());
            setOnInsert.put("occurred_at", txn.occurredAt().toString());
            setOnInsert.put("occurred_epoch_ms", txn.occurredAt().toInstant().toEpochMilli());
            setOnInsert.put("direction", txn.direction().name());
            setOnInsert.put("amount", txn.amount().toPlainString());
            setOnInsert.put("category", txn.category().name());
            setOnInsert.put("merchant", txn.merchant());

            Map<String, Object> eachClause = Map.of("$each", txn.sourceMessageIds());
            Map<String, Object> addToSet = Map.of("source_message_ids", eachClause);

            Map<String, Object> update = new LinkedHashMap<>();
            update.put("$setOnInsert", setOnInsert);
            update.put("$addToSet", addToSet);

            Method updateOneMethod = mongoCollClass.getMethod("updateOne", bsonClass, bsonClass, updateOptionsClass);
            updateOneMethod.invoke(this.collection, createDoc(filter), createDoc(update), options);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save transaction: " + txn, e);
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        Objects.requireNonNull(accountLast4, "accountLast4");
        Objects.requireNonNull(month, "month");

        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(Dates.IST);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(Dates.IST);

        Map<String, Object> range = new LinkedHashMap<>();
        range.put("$gte", start.toString());
        range.put("$lt", end.toString());

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("account_last4", accountLast4);
        filter.put("occurred_at", range);

        Map<String, Object> sort = Map.of("occurred_at", -1);

        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> bsonClass = Class.forName("org.bson.conversions.Bson");
            Class<?> findIterableClass = Class.forName("com.mongodb.client.FindIterable");

            Method findMethod = mongoCollClass.getMethod("find", bsonClass);
            Object findIterable = findMethod.invoke(this.collection, createDoc(filter));

            Method sortMethod = findIterableClass.getMethod("sort", bsonClass);
            Object sortedIterable = sortMethod.invoke(findIterable, createDoc(sort));

            List<NormalizedTxn> results = new ArrayList<>();
            for (Object docObj : (Iterable<?>) sortedIterable) {
                results.add(fromDocument(docObj));
            }
            return Collections.unmodifiableList(results);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query forAccountMonth", e);
        }
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Objects.requireNonNull(accountLast4, "accountLast4");

        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            totals.put(c, BigDecimal.ZERO.setScale(2));
        }

        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> bsonClass = Class.forName("org.bson.conversions.Bson");

            Map<String, Object> matchStage = Map.of("$match", Map.of("account_last4", accountLast4));
            Map<String, Object> groupStage = Map.of("$group", Map.of(
                    "_id", "$category",
                    "total", Map.of("$sum", Map.of("$toDecimal", "$amount"))));

            List<Object> pipeline = List.of(createDoc(matchStage), createDoc(groupStage));

            Method aggregateMethod = mongoCollClass.getMethod("aggregate", List.class);
            Object aggIterable = aggregateMethod.invoke(this.collection, pipeline);

            for (Object docObj : (Iterable<?>) aggIterable) {
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) docObj;
                String catName = (String) d.get("_id");
                Object totalObj = d.get("total");
                if (catName != null && totalObj != null) {
                    Category c = Category.valueOf(catName);
                    BigDecimal amount = new BigDecimal(totalObj.toString()).setScale(2);
                    totals.put(c, amount);
                }
            }
            return Collections.unmodifiableMap(totals);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to aggregate category totals", e);
        }
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Objects.requireNonNull(messageId, "messageId");

        Map<String, Object> filter = Map.of("source_message_ids", messageId);

        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> bsonClass = Class.forName("org.bson.conversions.Bson");
            Class<?> findIterableClass = Class.forName("com.mongodb.client.FindIterable");

            Method findMethod = mongoCollClass.getMethod("find", bsonClass);
            Object findIterable = findMethod.invoke(this.collection, createDoc(filter));

            Method firstMethod = findIterableClass.getMethod("first");
            Object firstDoc = firstMethod.invoke(findIterable);

            if (firstDoc == null) return Optional.empty();
            return Optional.of(fromDocument(firstDoc));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query byMessageId", e);
        }
    }

    public List<NormalizedTxn> all() {
        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Method findMethod = mongoCollClass.getMethod("find");
            Object findIterable = findMethod.invoke(this.collection);

            List<NormalizedTxn> results = new ArrayList<>();
            for (Object docObj : (Iterable<?>) findIterable) {
                results.add(fromDocument(docObj));
            }
            return Collections.unmodifiableList(results);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read all documents", e);
        }
    }

    public long count() {
        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Method countMethod = mongoCollClass.getMethod("countDocuments");
            return (long) countMethod.invoke(this.collection);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to count documents", e);
        }
    }

    public void dropCollection() {
        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Method dropMethod = mongoCollClass.getMethod("drop");
            dropMethod.invoke(this.collection);
            ensureIndexes();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to drop collection", e);
        }
    }

    @SuppressWarnings("unchecked")
    private NormalizedTxn fromDocument(Object docObj) {
        Map<String, Object> d = (Map<String, Object>) docObj;
        String acct = (String) d.get("account_last4");
        OffsetDateTime occurred = OffsetDateTime.parse((String) d.get("occurred_at"));
        Direction dir = Direction.valueOf((String) d.get("direction"));
        BigDecimal amount = new BigDecimal(String.valueOf(d.get("amount"))).setScale(2);
        Category cat = Category.valueOf((String) d.get("category"));
        String merchant = (String) d.getOrDefault("merchant", "");
        List<String> rawIds = (List<String>) d.get("source_message_ids");
        Set<String> sortedIds = new TreeSet<>(rawIds != null ? rawIds : List.of());

        return new NormalizedTxn(acct, occurred, dir, amount, cat, merchant, new ArrayList<>(sortedIds));
    }

    public record ExecutionStats(long totalDocsExamined, long nReturned) {}

    public ExecutionStats explainForAccountMonth(String accountLast4, YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(Dates.IST);
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(Dates.IST);

        Map<String, Object> filter = Map.of(
                "account_last4", accountLast4,
                "occurred_at", Map.of("$gte", start.toString(), "$lt", end.toString()));
        Map<String, Object> sort = Map.of("occurred_at", -1);

        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> bsonClass = Class.forName("org.bson.conversions.Bson");
            Class<?> findIterableClass = Class.forName("com.mongodb.client.FindIterable");
            Class<?> verbosityClass = Class.forName("com.mongodb.ExplainVerbosity");
            Object execStatsVerbosity = Enum.valueOf((Class<Enum>) verbosityClass, "EXECUTION_STATS");

            Object findIterable = mongoCollClass.getMethod("find", bsonClass)
                    .invoke(this.collection, createDoc(filter));
            findIterableClass.getMethod("sort", bsonClass).invoke(findIterable, createDoc(sort));

            Object explainDoc = findIterableClass.getMethod("explain", verbosityClass)
                    .invoke(findIterable, execStatsVerbosity);

            return parseStats(explainDoc);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to explain forAccountMonth", e);
        }
    }

    public ExecutionStats explainCategoryTotals(String accountLast4) {
        try {
            Map<String, Object> match = Map.of("$match", Map.of("account_last4", accountLast4));
            Map<String, Object> group = Map.of("$group", Map.of(
                    "_id", "$category",
                    "total", Map.of("$sum", Map.of("$toDecimal", "$amount"))));
            List<Object> pipeline = List.of(createDoc(match), createDoc(group));

            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> aggregateIterableClass = Class.forName("com.mongodb.client.AggregateIterable");
            Class<?> verbosityClass = Class.forName("com.mongodb.ExplainVerbosity");
            Object execStatsVerbosity = Enum.valueOf((Class<Enum>) verbosityClass, "EXECUTION_STATS");

            Object aggIterable = mongoCollClass.getMethod("aggregate", List.class)
                    .invoke(this.collection, pipeline);
            Object explainDoc = aggregateIterableClass.getMethod("explain", verbosityClass)
                    .invoke(aggIterable, execStatsVerbosity);

            return parseStats(explainDoc);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to explain categoryTotals", e);
        }
    }

    public ExecutionStats explainByMessageId(String messageId) {
        Map<String, Object> filter = Map.of("source_message_ids", messageId);
        try {
            Class<?> mongoCollClass = Class.forName("com.mongodb.client.MongoCollection");
            Class<?> bsonClass = Class.forName("org.bson.conversions.Bson");
            Class<?> findIterableClass = Class.forName("com.mongodb.client.FindIterable");
            Class<?> verbosityClass = Class.forName("com.mongodb.ExplainVerbosity");
            Object execStatsVerbosity = Enum.valueOf((Class<Enum>) verbosityClass, "EXECUTION_STATS");

            Object findIterable = mongoCollClass.getMethod("find", bsonClass)
                    .invoke(this.collection, createDoc(filter));
            Object explainDoc = findIterableClass.getMethod("explain", verbosityClass)
                    .invoke(findIterable, execStatsVerbosity);

            return parseStats(explainDoc);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to explain byMessageId", e);
        }
    }

    @SuppressWarnings("unchecked")
    private ExecutionStats parseStats(Object explainDoc) {
        Map<String, Object> doc = (Map<String, Object>) explainDoc;
        Map<String, Object> execStats = (Map<String, Object>) doc.get("executionStats");
        if (execStats == null) {
            return new ExecutionStats(0, 0);
        }
        long docsExamined = ((Number) execStats.getOrDefault("totalDocsExamined", 0L)).longValue();
        long nReturned = ((Number) execStats.getOrDefault("nReturned", 0L)).longValue();
        return new ExecutionStats(docsExamined, nReturned);
    }

    @Override
    public void close() {
        try {
            Method closeMethod = client.getClass().getMethod("close");
            closeMethod.invoke(client);
        } catch (Exception ignored) {}
    }
}
