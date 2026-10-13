package ee.yvonne.bank.idempotency;

import java.math.BigDecimal;
import java.util.Objects;

import ee.yvonne.bank.common.Currency;

public record IdempotencyRequestContent(BigDecimal amount, Currency currency, Currency toCurrency, Operation operation) {
    public IdempotencyRequestContent {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(operation, "operation is required");
        if (operation == Operation.EXCHANGE && toCurrency == null) {
            throw new IllegalArgumentException("toCurrency is required for an exchange");
        }
    }

    public static IdempotencyRequestContent debit(BigDecimal amount, Currency currency) {
        return new IdempotencyRequestContent(amount, currency, null, Operation.DEBIT);
    }

    public static IdempotencyRequestContent deposit(BigDecimal amount, Currency currency) {
        return new IdempotencyRequestContent(amount, currency, null, Operation.DEPOSIT);
    }

    public static IdempotencyRequestContent exchange(BigDecimal amount, Currency currency, Currency toCurrency) {
        return new IdempotencyRequestContent(amount, currency, toCurrency, Operation.EXCHANGE);
    }
}
