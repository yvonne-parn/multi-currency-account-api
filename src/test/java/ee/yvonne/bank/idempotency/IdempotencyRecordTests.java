package ee.yvonne.bank.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.common.Currency;

class IdempotencyRecordTests {
    Account account = new Account("TEST-1", "Holder Name");

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "10", "10.0"}) 
    void hasSameContentReturnsTrueIfAllMatch(String amount) {
        IdempotencyRequestContent recordContent = new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, Operation.DEBIT);
        IdempotencyRecord record = new IdempotencyRecord(account, UUID.randomUUID(), recordContent, UUID.randomUUID());

        IdempotencyRequestContent requestContent = new IdempotencyRequestContent(new BigDecimal(amount), Currency.EUR, null, Operation.DEBIT);
        boolean result = record.hasSameContent(requestContent);

        assertThat(result).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
                "Currency differs, 10.00, USD,, DEBIT", 
                "Amount differs, 10.05, EUR,, DEBIT", 
                "To currency present, 10.00, EUR, USD, DEBIT", 
                "Operation differs, 10.00, EUR,, DEPOSIT", 
                "All content differs, 1, USD, GBP, EXCHANGE"
        }) 
    void hasSameContentReturnsFalseIfAnyDiffer(String testName, String amountString, Currency currency, Currency toCurrency, Operation operation) {
        IdempotencyRequestContent recordContent = new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, Operation.DEBIT);
        IdempotencyRecord record = new IdempotencyRecord(account, UUID.randomUUID(), recordContent, UUID.randomUUID());
       
        IdempotencyRequestContent requestContent = new IdempotencyRequestContent(new BigDecimal(amountString), currency, toCurrency, operation);
        boolean result = record.hasSameContent(requestContent);

        assertThat(result).isFalse();
    }
}
