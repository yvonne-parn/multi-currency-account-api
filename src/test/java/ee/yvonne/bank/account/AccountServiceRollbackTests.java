package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest
class AccountServiceRollbackTests {
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountBalanceRepository balanceRepository;
    @MockitoSpyBean LedgerEntryRepository ledgerRepository;
    @MockitoBean ExternalLoggingClient externalLoggingClient;
    private AccountTestData testData;

    @BeforeEach
    void setUp() {
        testData = new AccountTestData(accountRepository, balanceRepository, ledgerRepository, accountService);
    }

    @Test
    void ledgerFailureLeavesExistingBalanceUnchanged() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void ledgerFailureOnFirstDepositCreatesNoBalanceRow() {
        Account account = testData.newAccount();

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("50.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.USD)).isEmpty();
        assertThat(testData.ledgerOf(account)).isEmpty();
    }

    @Test
    void ledgerFailureDuringDebitLeavesBalanceUnchanged() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"));

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("30.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(EntryType.class)
    void ledgerFailureDuringExchangeLeavesBalancesUnchanged(EntryType entryType) {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"));

        doThrow(new DataAccessResourceFailureException("ledger down"))
            .when(ledgerRepository)
            .save(argThat((LedgerEntry entry) ->
                entry.getType() == entryType
            ));

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.balanceOf(account, Currency.USD).isPresent()).isFalse();
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void ledgerFailureDuringExchangeLeavesExistingTargetBalanceUnchanged() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"));
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("10.00"));

        doThrow(new DataAccessResourceFailureException("ledger down"))
                .when(ledgerRepository)
                .save(argThat((LedgerEntry entry) -> entry.getType() == EntryType.CREDIT));

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.balanceOf(account, Currency.USD)
                .orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(2);
    }
}
