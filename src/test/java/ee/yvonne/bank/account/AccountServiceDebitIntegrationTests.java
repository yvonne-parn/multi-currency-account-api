package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.ExternalSystemException;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest(properties = { "exchange.rates.EUR=1.0", "exchange.rates.USD=1.12", "exchange.rates.SEK=11.25", "exchange.rates.GBP=0.85" })
class AccountServiceDebitIntegrationTests {
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountBalanceRepository balanceRepository;
    @Autowired private LedgerEntryRepository ledgerRepository;
    @MockitoBean ExternalLoggingClient externalLoggingClient;
    private AccountTestData testData;

    @BeforeEach
    void setUp() {
        testData = new AccountTestData(accountRepository, balanceRepository, ledgerRepository, accountService);
    }

     @Test
    void successfulDebitReducesBalanceAndWritesLedgerEntry() {
        Account account = testData.accountWith(Currency.EUR, "100.00");

        AccountBalanceResult result = accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("30.00"));

        assertThat(result.balance()).isEqualByComparingTo("70.00");
        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("70.00");
        List<LedgerEntry> entries = testData.ledgerOf(account);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getType()).isEqualTo(EntryType.DEBIT);
        assertThat(entries.get(1).getNewBalance()).isEqualByComparingTo("70.00");
        verify(externalLoggingClient).logDebitAttempt();
    }

    @Test
    void externalFailureRejectsDebitAndChangesNothing() {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        doThrow(new ExternalSystemException("system down")).when(externalLoggingClient).logDebitAttempt();

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("30.00")))
                .isInstanceOf(ExternalSystemException.class);

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void insufficientFundsLeavesBalanceAndLedgerUnchanged() {
        Account account = testData.accountWith(Currency.EUR, "10.00");

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void debitInCurrencyTheAccountNeverHeldIsInsufficientFundsAndCreatesNoRow() {
        Account account = testData.accountWith(Currency.EUR, "10.00");

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.USD, new BigDecimal("1.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(testData.balanceOf(account, Currency.USD)).isEmpty();
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void externalCallHappensOutsideAnyTransaction() {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(externalLoggingClient).logDebitAttempt();

        accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1.00"));
    }

    @Test 
    void debitFromUnknownAccountThrowsAndExternalCallIsNotMade() {
        assertThatThrownBy(() -> accountService.debit("NON-EXISTENT-DEBIT", Currency.EUR, new BigDecimal("10.00")))
            .isInstanceOf(AccountNotFoundException.class);

        verify(externalLoggingClient, never()).logDebitAttempt();
    }

    @Test
    void debitWritesLedgerEntryWithRunningBalance() {
        Account account = testData.accountWith(Currency.EUR, "100.00");

        accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("20.00"));

        List<LedgerEntry> debitEntries = testData.ledgerOf(account).stream()
            .filter(l -> l.getType() == EntryType.DEBIT).toList();

        assertThat(debitEntries).hasSize(2);
        assertThat(debitEntries).allSatisfy(e -> {
            assertThat(e.getCurrency()).isEqualTo(Currency.EUR);
            assertThat(e.getType()).isEqualTo(EntryType.DEBIT);
            assertThat(e.getCorrelationId()).isNotNull();
        });
        assertThat(debitEntries.get(0).getAmount()).isEqualByComparingTo("10.00");
        assertThat(debitEntries.get(0).getNewBalance()).isEqualByComparingTo("90.00");
        assertThat(debitEntries.get(1).getAmount()).isEqualByComparingTo("20.00");
        assertThat(debitEntries.get(1).getNewBalance()).isEqualByComparingTo("70.00");
    }  
}
