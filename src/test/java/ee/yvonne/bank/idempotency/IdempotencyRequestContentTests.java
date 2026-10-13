package ee.yvonne.bank.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ee.yvonne.bank.common.Currency;

class IdempotencyRequestContentTests {
    
    @ParameterizedTest 
    @EnumSource(
        value = Operation.class,
        names = {"DEPOSIT", "DEBIT"}
    )
    void nonNullFieldsAreAccepted(Operation operation) {
        IdempotencyRequestContent content = new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, operation);

        assertThat(content.amount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(content.currency()).isEqualTo(Currency.EUR);
        assertThat(content.toCurrency()).isNull();
        assertThat(content.operation()).isEqualTo(operation);
    }

    @Test 
    void exchangeContentPassesWhenAllFieldsFilled() {
        IdempotencyRequestContent content = new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, Currency.USD, Operation.EXCHANGE);

        assertThat(content.amount()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(content.currency()).isEqualTo(Currency.EUR);
        assertThat(content.toCurrency()).isEqualTo(Currency.USD);
        assertThat(content.operation()).isEqualTo(Operation.EXCHANGE);
    }

    @Test 
    void exchangeContentThrowsNullPointerExceptionWhenToCurrencyMissing() {
        assertThatThrownBy(() -> new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, Operation.EXCHANGE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toCurrency");
    }

    @ParameterizedTest 
    @EnumSource(Operation.class) 
    void contentThrowsNullPointerExceptionIfAmountMissing(Operation operation) {
        assertThatThrownBy(() -> new IdempotencyRequestContent(null, Currency.EUR, null, operation))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("amount");
    }

    @ParameterizedTest 
    @EnumSource(Operation.class) 
    void contentThrowsNullPointerExceptionIfCurrencyMissing(Operation operation) {
        assertThatThrownBy(() -> new IdempotencyRequestContent(BigDecimal.ONE, null, null, operation))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void contentThrowsNullPointerExceptionIfOperationMissing() {
        assertThatThrownBy(() -> new IdempotencyRequestContent(BigDecimal.ONE, Currency.EUR, null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("operation");
    }

    @Test
    void depositFactorySetsDepositOperationAndNoToCurrency() {
        IdempotencyRequestContent content = IdempotencyRequestContent.deposit(new BigDecimal("10.00"), Currency.EUR);

        assertThat(content.operation()).isEqualTo(Operation.DEPOSIT);
        assertThat(content.currency()).isEqualTo(Currency.EUR);
        assertThat(content.toCurrency()).isNull();
        assertThat(content.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void debitFactorySetsDebitOperationAndNoToCurrency() {
        IdempotencyRequestContent content = IdempotencyRequestContent.debit(new BigDecimal("10.00"), Currency.EUR);

        assertThat(content.operation()).isEqualTo(Operation.DEBIT);
        assertThat(content.currency()).isEqualTo(Currency.EUR);
        assertThat(content.toCurrency()).isNull();
        assertThat(content.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void exchangeFactorySetsBothCurrenciesAndExchangeOperation() {
        IdempotencyRequestContent content = IdempotencyRequestContent.exchange(new BigDecimal("10.00"), Currency.EUR, Currency.USD);

        assertThat(content.operation()).isEqualTo(Operation.EXCHANGE);
        assertThat(content.currency()).isEqualTo(Currency.EUR);
        assertThat(content.toCurrency()).isEqualTo(Currency.USD);
        assertThat(content.amount()).isEqualByComparingTo("10.00");
    }
}
