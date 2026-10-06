package ee.yvonne.bank.account;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.InsufficientFundsException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountBalanceTests {
    AccountBalance balance;

    @BeforeEach
    void setUp() {
        balance = new AccountBalance(new Account("TEST-0001", "Test Holder"), Currency.EUR);
    }

    @Test
    void newBalanceStartsAtZero() {
        assertThat(balance.getBalance()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void credit_increasesBalance() {
        balance.credit(new BigDecimal("10.00"));

        assertThat(balance.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void credit_handlesDecimalsExactly() {
        balance.credit(new BigDecimal("0.10"));
        balance.credit(new BigDecimal("0.20"));

        assertThat(balance.getBalance()).isEqualByComparingTo("0.30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void credit_rejectsNonPositiveAmount(String amount) {
        assertThatThrownBy(() -> balance.credit(new BigDecimal(amount))).isInstanceOf(IllegalArgumentException.class);
        assertThat(balance.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void credit_rejectsNullAmount() {
        assertThatThrownBy(() -> balance.credit(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void debit_reducesBalance() {
        balance.credit(new BigDecimal("10.00"));

        balance.debit(new BigDecimal("1.00"));

        assertThat(balance.getBalance()).isEqualByComparingTo("9.00");
    }

    @Test
    void debit_ofExactBalanceLeavesZero() {
        balance.credit(new BigDecimal("10.00"));

        balance.debit(new BigDecimal("10.00"));

        assertThat(balance.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void debit_throws_whenInsufficientFunds() {
        balance.credit(new BigDecimal("10.00"));

        assertThatThrownBy(() -> balance.debit(new BigDecimal("10.01"))).isInstanceOf(InsufficientFundsException.class);
        assertThat(balance.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void debit_throws_onEmptyBalance() {
        assertThatThrownBy(() -> balance.debit(BigDecimal.ONE)).isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    void debit_rejectsNonPositiveAmount(String amount) {
        balance.credit(new BigDecimal("10.00"));

        assertThatThrownBy(() -> balance.debit(new BigDecimal(amount))).isInstanceOf(IllegalArgumentException.class);
        assertThat(balance.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void debit_rejectsNullAmount() {
        assertThatThrownBy(() -> balance.debit(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
