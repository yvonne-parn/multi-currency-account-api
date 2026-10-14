# Multi-currency account API

A RESTful banking account API built with Java and Spring Boot that supports multi-currency accounts, deposits, debits and currency exchanges.

## Features

- Multi-currency accounts with separate EUR, USD, SEK and GBP balances
- Deposits and debits in a single currency per request
- Currency exchange using configurable fixed rates
- Idempotent POST requests

## Tech stack

- Java 17
- Spring Boot 4.1.1
- H2 in-memory database
- Maven
- Swagger UI (OpenAPI)
- JUnit 5, AssertJ, Mockito, MockMvc

## Getting started

Requires JDK 17.

Start the application:
```bash
./mvnw spring-boot:run
```

- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- H2 console: `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:bankdb`, user `sa`)

The database is in memory, so data is lost on restart. A test account is created at startup: `EE000000000001`.

Run the tests:

```bash
./mvnw test
```

## API

| Method | Path | Description |
|--------|------|---------|
| GET | `/accounts/{accountNumber}/balance` | Retrieve all existing currency balances |
| POST | `/accounts/{accountNumber}/deposit` | Add money in one currency |
| POST | `/accounts/{accountNumber}/debit` | Withdraw money in one currency |
| POST | `/accounts/{accountNumber}/exchange` | Convert money between two currencies |

### Example requests

Amounts must be positive and have at most two decimal places.
All POST requests require an `Idempotency-Key` header containing a UUID.

#### Get balances

```bash
curl http://localhost:8080/accounts/EE000000000001/balance
```

#### Deposit

```bash
curl -X POST http://localhost:8080/accounts/EE000000000001/deposit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount": 50.00, "currency": "EUR"}'
```

#### Debit

```bash
curl -X POST http://localhost:8080/accounts/EE000000000001/debit \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount": 20.00, "currency": "EUR"}'
```

#### Exchange

```bash
curl -X POST http://localhost:8080/accounts/EE000000000001/exchange \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"fromCurrency": "EUR", "toCurrency": "USD", "amount": 100.00}'
```

### Error responses

Errors use the standard `ProblemDetail` format (RFC 9457).

| Status | Meaning |
|--------|------|
| 400 | Invalid request or unsupported operation; missing or malformed `Idempotency-Key` |
| 404 | Account not found |
| 409 | Idempotency conflict |
| 422 | Insufficient funds |
| 502 | External logging system failure |
| 500 | Unexpected server error |

## Configuration

Configuration is defined in `src/main/resources/application.properties`:

- `exchange.rates.<CURRENCY>` – fixed exchange rates expressed as units of currency per EUR
- `external.logging.url` – external endpoint called before each debit
- `external.logging.connect-timeout` – connection timeout
- `external.logging.read-timeout` – response timeout
- `idempotency.retention` – idempotency records retention time
- `idempotency.cleanup-cron` – cron expression defining the schedule for removing expired idempotency records

Default exchange rates: EUR 1.0, USD 1.12, SEK 11.25, GBP 0.85.

The default external logging endpoint returns HTTP 200. To test debit failure handling, change `external.logging.url` to an endpoint that returns a non-2xx response, such as `https://httpbin.org/status/500`.

## Design decisions

- **Data model.** Four tables: `account`, `account_balance` (one row per account and currency), `ledger_entry` and `idempotency_record`. A balance is created on the first deposit or exchange into a currency.

- **Ledger.** Every balance change also appends an immutable ledger entry, written in the same transaction as the balance itself. An exchange creates two entries – a debit and a credit, linked by a correlation ID. The ledger is single-entry rather than a full double-entry accounting system.

- **Money handling.** `BigDecimal` is used everywhere, stored as `DECIMAL(19,2)`. All four supported currencies have two decimals, so the scale is fixed at 2.

- **Concurrency.** Every operation that changes the balance takes a pessimistic write lock on the account to prevent concurrent requests from overwriting one another.

- **External call.** The external logging call is made before the debit transaction starts, so the database lock is not held while waiting for the external system. If the external call succeeds but the debit later fails, the external call cannot be rolled back. The logging call is made for debit requests only, not exchanges.

- **Idempotency.** All POST requests require an `Idempotency-Key` header containing a UUID that's unique per account. Repeating a request with the same key and payload returns the original result without processing the operation again. Reusing a key with different request content returns `409 Conflict`. Idempotency records are stored in the database and expire after the configured retention period. Duplicate requests are checked inside the transaction, under the account lock, to prevent concurrent duplicate operations. Debit requests also perform an initial check before the external logging call to avoid unnecessary calls.

## Known limitations

- No authentication or authorization.
- Fixed exchange rates; no live exchange rate provider.
- In-memory H2 database; data is lost on application stop or restart.
- Single-entry ledger; no full double-entry accounting.
- All supported currencies use a fixed two-decimal scale.
- No combined total balance across currencies is calculated.
- Accounts and currency balances cannot be created through the API; the application starts with a seeded account. Currency balances are created when a currency is first credited.
- The exchange rate in the response is informational. Conversion uses the configured rates and rounds the resulting amount once. On a duplicate exchange request, the amounts and balances are the original ones, but the displayed rate may differ if the configuration has changed.
- Concurrent duplicate first-time debit requests may both trigger the external logging call, but the idempotency check under the account lock ensures that the debit is processed only once.

## Testing
 
- **Unit tests:** Entity rules, exchange-rate configuration validation, idempotency logic and currency conversion.
- **Controller tests** (`@WebMvcTest`, service mocked): HTTP status codes, request validation and error responses.
- **Service integration tests** (`@SpringBootTest`, using real H2 database): Deposits, debits, exchanges, balances and ledger entries.
- **Rollback tests:** Verify that a failure during a balance-changing transaction leaves balances and ledger entries unchanged.
- **Concurrency tests:** Parallel deposits, debits and opposite-direction exchanges on the same account, verifying that updates are not lost and the balance is never overdrawn. Duplicate requests using the same idempotency key are also tested to verify that money moves only once.
- **External HTTP client tests:** Verify successful responses and failure handling using a mocked HTTP server, without making real network calls.
