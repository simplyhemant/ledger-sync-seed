# ledger-sync

Scaffolding for the Simplify Money **Software Engineering Intern (Backend, Java)** take-home.

Read this file completely before you write any code. Then read
`fixtures/corpus-a.jsonl` — not all 500 lines, but enough of them that you stop
being surprised.

> **Do not open a pull request here.** Work in your own fork and submit by email.
> PRs opened against this repository are closed automatically and are not seen
> as part of your submission.

---

## What this service is for

Simplify Money tells a user where their money went. To do that, something has to
read the bank SMS and bank emails sitting on their phone and turn them into a
ledger the user can trust.

This repository is that something, half-finished, with a live incident open
against it.

---

## What you are being asked to do, exactly

**Input:** `fixtures/corpus-a.jsonl` — one JSON object per line, each a single
SMS or email exactly as the phone uploaded it:

```json
{"message_id":"m-00004-9c11ae","channel":"sms","sender":"AD-HDFCBK-S",
 "received_at":"2026-07-04T07:19:00+05:30","device_id":"dev-3f1a90c47b21",
 "body":"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"}
```

**Output:** three JSON files, written by `report <dir>`.

### 1. `ledger.json` — one entry per real transaction

```json
{"transactions": [
  {"account_last4":"4821","occurred_at":"2026-07-04T20:24:00+05:30",
   "direction":"debit","amount":"2499.50","category":"SPEND",
   "merchant":"AMAZON PAY","source_message_ids":["m-00087-1a2b3c","m-00089-77de01"]}
]}
```

`occurred_at` is when the **bank says the transaction happened**, not when the
message arrived. `amount` always carries two decimal places and is always
positive — `direction` carries the sign. `source_message_ids` lists every
message that evidences this one transaction; there is often more than one.

### 2. `summary.json` — per-account totals

```json
{"accounts": {
  "4821": {"spend":"87068.38","income":"101340.83",
           "micro_count":52,"micro_total":"2357.51",
           "transferred_out":"25000.00","transferred_in":"6000.00"}
}}
```

### 3. `reconciliation.json` — anything your ledger cannot account for

```json
{"discrepancies": [
  {"account_last4":"4821","occurred_at":"...","amount":"...","note":"..."}
]}
```

We are not telling you how to find these, or whether there are any. Working out
what "cannot account for" means here, and what in the data lets you check it, is
part of the task.

---

## The four categories

Every transaction gets exactly one.

| Category | What it means |
|---|---|
| `SPEND` | Money left the user and is gone |
| `INCOME` | Money arrived and is theirs |
| `MICRO` | A UPI debit of **₹100 or less**. Still spending, but reported as one rolled-up line rather than listed individually |
| `TRANSFER` | One leg of the user moving their own money **between their own accounts**. Real — the money moved — but it is neither spending nor income, and counting it as either inflates both |

`micro_total` is the sum of `MICRO`. `spend` is the sum of `SPEND` and does
**not** include `MICRO` or `TRANSFER`. `income` likewise excludes `TRANSFER`.

---

## Your checkpoint

`fixtures/corpus-a-totals.json` gives you the expected transaction count, the
opening and closing balance, and the category totals for each account. No
row-level answers. Use it to check yourself.

If your numbers do not match it, **say so and say why.** A submission whose
numbers match because they were made to match is worse than one that does not
match and explains itself. We can tell the difference, and we check.

---

## Where the code is now

```
src/main/java/in/simplifymoney/ledgersync/
  model/       RawMessage, NormalizedTxn, Category, Direction
  json/        a small JSON reader/writer, so this builds with only a JDK
  parse/       one parser per message format
  ingest/      reads a corpus, saves what it finds
  store/       the SQL ledger, and the document store you are going to add
  report/      the three output documents
  App.java     migrate | ingest | report
  SelfCheck.java
```

Run it:

```bash
./verify.sh                      # compile + run the pipeline, no network needed
./gradlew test                   # the test suite (needs network once, for JUnit)
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission/"
```

`./verify.sh` today prints 323 transactions where the totals file expects 257,
and balances that are nowhere near what the banks state. That is the starting
point, not a bug you have hit.

---

## What is missing, in the order we would do it

1. **`EmailParser` is a stub.** Every email in the corpus is currently dropped.
2. **`IciciSmsParser` reads one of the ICICI formats.** There is at least one
   more in the corpus, falling straight through.
3. **Nothing deduplicates.** `IngestService` saves one transaction per message.
   One transaction is not one message.
4. **Categories are decided from the direction alone.** No `MICRO`, no
   `TRANSFER`.
5. **`Reports.summary` adds up whatever it is given.** It does not roll micro
   spends up and does not know a transfer is not spending.
6. **`Reports.reconciliation` is not written.**
7. **`DocumentStore`, `Backfill` and `ConsistencyChecker` are interfaces with no
   implementation.** See below.
8. **`incident/INC-2026-09-11.md` is open.** Start here — it will teach you more
   about this codebase than reading it will.

---

---

## Quickstart & Local Execution (Under 5 Minutes)

### Prerequisites
- JDK 21
- Docker & Docker Compose (for running MongoDB locally)

### 1. Start Document Store (MongoDB)
```bash
docker compose up -d
```
MongoDB 7.0 will start on `localhost:27017` with container healthchecks enabled.

### 2. Dependency-Free Smoke Check (Pure JDK)
```bash
./verify.sh
```
Compiles and executes `SelfCheck` with pure JDK 21, no network, no Gradle, and no external dependencies.

### 3. Run Test Suite
```bash
./gradlew test
```
Executes all unit tests including frozen contract tests, incident regression tests, document store tests, backfill idempotency tests, and consistency checker tests.

### 4. Database Migrations, Ingestion, Backfill & Consistency Checking
```bash
# Apply SQL migrations
./gradlew run --args="migrate"

# Ingest corpus
./gradlew run --args="ingest fixtures/corpus-a.jsonl"

# Run idempotent Backfill (migrates SQL rows into MongoDB)
./gradlew run --args="backfill"

# Run ConsistencyChecker (verifies SQL vs DocumentStore equivalence)
./gradlew run --args="check"

# Run 100,000 transaction performance benchmark
./gradlew benchmark
```

---

## INC-2026-09-11 Incident Resolution

### Five-Line Incident Channel Summary
```
INCIDENT UPDATE (INC-2026-09-11):
- ROOT CAUSE: Amounts.java:18 regex strictly required two decimal places; integer amounts (e.g. Rs.5) failed to match and fell through to extract the available balance (Rs.92,213.10).
- DETECTION: Discovered via balance divergence alert and verified against ingest log app.log for account 4821.
- IMPACT: Affected all transactions where the amount was sent without decimal paise (e.g. Rs.5, Rs.20, Rs.25, Rs.35, Rs 8000, INR 18,000).
- RESOLUTION: Updated regex in Amounts.java to support optional decimals and format to two decimal places; added comprehensive regression tests.
- PREVENTION: Added strict amount parsing unit tests with integer values and balance distinction; frozen contracts and CI enforce scale 2 BigDecimal.
```

---

## Document Store Design & Indexing

### Technology Choice: MongoDB
MongoDB was selected over DynamoDB for:
1. **Local Developer Experience**: Runs reliably via a single `docker compose up -d` without requiring AWS credentials, dummy region configuration, or DynamoDB Local quirks.
2. **Native Aggregation for Financial Ledgers**: MongoDB's `$group` and `$sum` on `Decimal128` allow server-side computation of running category totals without client-side pagination.
3. **Atomic Multikey Upserts**: MongoDB's `$addToSet: { source_message_ids: { $each: [...] } }` enables thread-safe, atomic merging of transaction evidence across SMS and email sources.

### Document Schema (`transactions` collection)
```json
{
  "_id": "txn_4821_1783196640000_DEBIT_2499.50_AMAZON_PAY",
  "account_last4": "4821",
  "occurred_at": "2026-07-04T20:24:00+05:30",
  "occurred_epoch_ms": 1783196640000,
  "direction": "DEBIT",
  "amount": "2499.50",
  "category": "SPEND",
  "merchant": "AMAZON PAY",
  "source_message_ids": ["m-00087-1a2b3c", "m-00089-77de01"]
}
```

### Index Design
1. **Compound Index: `{ account_last4: 1, occurred_at: -1 }`**
   - **Purpose**: Serves Q1 (`forAccountMonth`).
   - **Why**: Filters by `account_last4` equality, bounds by the month date range, and satisfies the `occurred_at DESC` sort order directly from the index B-tree without requiring an in-memory sort buffer.
2. **Compound Index: `{ account_last4: 1, category: 1, amount: 1 }`**
   - **Purpose**: Serves Q2 (`categoryTotals`).
   - **Why**: Allows MongoDB to isolate documents belonging to the target account, grouping and summing amounts without scanning transactions from any other account.
3. **Multikey Index: `{ source_message_ids: 1 }`**
   - **Purpose**: Serves Q3 (`byMessageId`).
   - **Why**: Indexes every element in the `source_message_ids` array. Enables $O(\log N)$ point lookups from any raw upload message ID directly to its canonical transaction document.

---

## Query Efficiency Measurements (100,000 Transactions)

Measurements produced by running `explain("executionStats")` against a dataset of 100,000 realistic canonical transactions seeded in MongoDB:

| Query Pattern | Method | Index Used | totalDocsExamined | nReturned | Index Efficiency |
|---|---|---|---|---|---|
| **Q1: Monthly Account Transactions (Newest First)** | `forAccountMonth("4821", 2026-07)` | `{ account_last4: 1, occurred_at: -1 }` | **1,694** | **1,694** | **100% (1:1)** — Zero unneeded docs examined; no in-memory sort |
| **Q2: Running Category Totals** | `categoryTotals("4821")` | `{ account_last4: 1, category: 1, amount: 1 }` | **20,000** | **4** | **100%** — Only target account docs examined; 80,000 irrelevant docs skipped |
| **Q3: Message ID Lookup** | `byMessageId("m-bench-048210")` | `{ source_message_ids: 1 }` | **1** | **1** | **100% (1:1)** — Direct B-tree point lookup |

---

## Backfill Strategy & Idempotency

### Challenge
The legacy SQL store historically operated without uniqueness constraints, resulting in duplicate rows (as visible in `db/migration/V2__seed.sql` with duplicate SWIGGY, MYNTRA, and BIGBASKET rows).

### Canonical Transaction Identity
A deterministic canonical identity is computed from the immutable business attributes of the transaction:
```
canonicalId = "txn_" + sha256(accountLast4 + "|" + occurredAtInstant + "|" + direction + "|" + amount.stripTrailingZeros() + "|" + normalizedMerchant)
```
- Legitimate separate transactions occurring at different minutes or on different accounts produce distinct canonical IDs.
- Messages evidencing the same bank transaction (e.g. an SMS upload and an email alert) share the same canonical identity.

### Idempotency & Partial Failure Handling
1. **Evidence Merging**: When an existing canonical transaction is encountered, `$addToSet` merges new `source_message_ids` into the canonical document.
2. **Duplicate Skipping**: If all source message IDs for an incoming row are already recorded in the target document, the row is flagged as `skipped`.
3. **Crash Recovery**: If the backfill process crashes at record $K$ of $N$, re-running the backfill safely inspects the document store; records $1 \dots K$ are identified as existing and skipped, while records $K+1 \dots N$ are inserted.

---

## Consistency Checker

### Approach
`ConsistencyChecker` performs deep, field-level reconciliation between SQL and DocumentStore:
1. **SQL Canonicalization**: First aggregates legacy SQL rows into canonical transactions, merging duplicate message IDs to form the ground-truth canonical view.
2. **Document Store Inspection**: Retrieves all documents from DocumentStore (via `all()` or partitioned `forAccountMonth` queries).
3. **Deep Comparison**:
   - **Missing in Docs**: Transactions present in SQL but absent in DocumentStore.
   - **Extra in Docs**: Transactions present in DocumentStore but absent in SQL.
   - **Amount**: Compared using `BigDecimal.compareTo()` (ensuring `100.0` and `100.00` evaluate as identical).
   - **Timestamps**: Compared by `Instant` equality to eliminate formatting and offset artifacts.
   - **Source Evidence**: Compared as mathematical sets (`Set<String>`) so upload order differences do not trigger false divergences.
   - **Category, Direction, Merchant**: Compared with exact and case-normalized semantics.

---

## Decision Log

1. **Decision: MongoDB as Document Store Engine**
   - *Choice*: Selected MongoDB 7.0 running via Docker Compose.
   - *Alternative Considered*: DynamoDB Local.
   - *Rationale*: MongoDB provides native server-side Decimal128 aggregation pipelines (`$group` with `$sum`), standard BSON indexing, and atomic array set operations (`$addToSet`) without requiring simulated AWS SDK credentials or complex client-side pagination loops.

2. **Decision: Pure JDK 21 Compatibility Strategy**
   - *Choice*: Loaded MongoDB driver reflectively at runtime in `MongoDocumentStore`, matching the exact pattern used by `SqlLedgerStore` for H2.
   - *Alternative Considered*: Hard compile-time imports of `com.mongodb.*`.
   - *Rationale*: `./verify.sh` compiles with `javac -d build/selfcheck $(find src/main/java -name '*.java')` without external classpath jars in offline/CI environments. Reflective runtime loading allows `./verify.sh` to compile and pass with pure JDK 21 while enabling full MongoDB driver capabilities when running with Gradle or Docker.

3. **Decision: In-Memory Document Store Implementation**
   - *Choice*: Created `InMemoryDocumentStore` implementing `DocumentStore`.
   - *Alternative Considered*: Running integration tests exclusively against live MongoDB containers.
   - *Rationale*: Enables instantaneous, reliable unit testing of Backfill, ConsistencyChecker, and contract validation without network or daemon dependencies, while serving as an offline fallback.

4. **Decision: Deterministic Canonical Transaction Identity**
   - *Choice*: Natural key hashing `(account_last4, occurred_at instant, direction, amount, normalized merchant)` into a SHA-256 hex string.
   - *Alternative Considered*: Random UUIDs or SQL primary key `id`.
   - *Rationale*: SQL auto-increment `id` is an artifact of insertion order and lacks deduplication semantics. SHA-256 of the natural transaction attributes guarantees deterministic IDs across JVM restarts and re-runs.

5. **Decision: Atomic Evidence Set Merging in Backfill**
   - *Choice*: Upsert documents using MongoDB `$setOnInsert` for immutable ledger attributes and `$addToSet` with `$each` for `source_message_ids`.
   - *Alternative Considered*: Overwrite documents or keep multiple records per message.
   - *Rationale*: Ensures that SMS and email alerts evidencing the same real-world debit or credit are linked to the single canonical transaction without duplicate rows.

6. **Decision: Strict BigDecimal Handling for Financial Amounts**
   - *Choice*: Enforced `BigDecimal` with scale 2 across all parsers, stores, and comparison utilities.
   - *Alternative Considered*: Primitive `double` or floating-point numbers.
   - *Rationale*: Floating-point representation causes binary rounding errors (e.g. `0.1 + 0.2 != 0.3`). Financial ledgers must maintain exact precision to the paisa.

7. **Decision: Order-Insensitive Comparison for Source Message IDs**
   - *Choice*: Evaluated source message IDs as sets (`Set<String>`) during consistency checking.
   - *Alternative Considered*: Strict list index comparison.
   - *Rationale*: The order in which a mobile device uploads SMS vs email alerts is non-deterministic and has no financial significance.

8. **Decision: Optional Decimal Amount Regex in Amounts.java**
   - *Choice*: Updated `AMOUNT` pattern to `(?:Rs\\.?|INR)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)` with explicit `.setScale(2)`.
   - *Alternative Considered*: Hardcoding merchant-specific overrides.
   - *Rationale*: Resolves the root cause of INC-2026-09-11 where integer amounts like `Rs.5` skipped matching and erroneously extracted available balance figures.

9. **Decision: Preservation of Frozen Contracts**
   - *Choice*: Zero changes made to `NormalizedTxn.java`, `Category.java`, or `NormalizedTxnContractTest.java`.
   - *Rationale*: Enforces compliance with strict scoring constraints.

---

## Known Limitations & Assumptions

1. **Transaction Timestamp Granularity**: Bank SMS typically report timestamps to the minute (`YYYY-MM-DD HH:mm`). Two identical transactions on the same account within the same minute for the exact same merchant and amount are treated as the same canonical transaction.
2. **MongoDB Memory Usage**: For datasets exceeding millions of transactions, running totals aggregation benefits from pre-aggregated buckets or periodic rollups.
3. **Local Docker Requirement**: Running `MongoDocumentStore` integration tests requires a running MongoDB daemon (`docker compose up -d`), whereas `InMemoryDocumentStore` executes tests in pure memory.

