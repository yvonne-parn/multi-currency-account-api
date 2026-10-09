package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.account.dto.ExchangeResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.ExternalSystemException;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.common.InvalidCurrencyExchangeException;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest(properties = { "exchange.rates.EUR=1.0", "exchange.rates.USD=1.12", "exchange.rates.SEK=11.25", "exchange.rates.GBP=0.85" })
class AccountServiceIntegrationTests {
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountBalanceRepository balanceRepository;
    @Autowired private LedgerEntryRepository ledgerRepository;
    @MockitoBean ExternalLoggingClient externalLoggingClient;

    private Account newAccount() {
        return accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
    }

    private Account accountWith(Currency currency, String amount) {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), currency, new BigDecimal(amount));
        return account;
    }

    private Optional<AccountBalance> balanceOf(Account account, Currency currency) {
        return balanceRepository.findByAccountIdAndCurrency(account.getId(), currency);
    }

    @Test
    void firstDepositInNewCurrencyCreatesBalanceRow() {
        Account account = newAccount();

        AccountBalanceResult result = accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("100.00"));

        assertThat(result.balance()).isEqualByComparingTo("100.00");
        assertThat(balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void depositInExistingCurrencyAddsToBalance() {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));

        AccountBalanceResult result = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("25.00"));

        assertThat(balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("35.00");
        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.currency()).isEqualTo(Currency.EUR);
        assertThat(result.balance()).isEqualByComparingTo("35.00");
    }

    @Test
    void depositsInDifferentCurrenciesStaySeparate() {
        Account account = newAccount();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("20.00"));

        assertThat(balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
        assertThat(balanceOf(account, Currency.SEK)).isEmpty();
    }

    @Test
    void depositToUnknownAccountThrows() {
        assertThatThrownBy(() -> accountService.deposit("NON-EXISTENT", Currency.EUR, new BigDecimal("10.00"))).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void depositWritesLedgerEntryWithRunningBalance() {
        Account account = newAccount();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("20.00"));

        List<LedgerEntry> entries = ledgerRepository.findByAccountIdOrderByIdAsc(account.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.getCurrency()).isEqualTo(Currency.EUR);
            assertThat(e.getType()).isEqualTo(EntryType.CREDIT);
            assertThat(e.getCorrelationId()).isNotNull();
        });
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo("10.00");
        assertThat(entries.get(0).getNewBalance()).isEqualByComparingTo("10.00");
        assertThat(entries.get(1).getAmount()).isEqualByComparingTo("20.00");
        assertThat(entries.get(1).getNewBalance()).isEqualByComparingTo("30.00");
    }

    @Test
    void rejectedDepositLeavesNoBalanceRowAndNoLedgerEntry() {
        Account account = newAccount();

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.GBP, new BigDecimal("-5.00"))).isInstanceOf(IllegalArgumentException.class);

        assertThat(balanceOf(account, Currency.GBP)).isEmpty();
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).isEmpty();
    }

    @Test 
    void getBalancesReturnsCorrectResult() {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("20.00"));

        AccountBalancesResult result = accountService.getBalances(account.getAccountNumber());

        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.balances())
            .extracting(CurrencyBalance::currency, CurrencyBalance::amount)
            .containsExactlyInAnyOrder(
                    tuple(Currency.EUR, new BigDecimal("10.00")),
                    tuple(Currency.USD, new BigDecimal("20.00")));
    }

    @Test
    void getBalancesReturnsOnlyThatAccountsBalances() {
        Account first = accountWith(Currency.EUR, "10.00");
        accountWith(Currency.USD, "99.00");

        AccountBalancesResult result = accountService.getBalances(first.getAccountNumber());

        assertThat(result.balances()).extracting(CurrencyBalance::currency).containsExactly(Currency.EUR);
    }

    @Test 
    void getBalancesForUnknownAccountThrows() {
        assertThatThrownBy(() -> accountService.getBalances("NON-EXISTENT")).isInstanceOf(AccountNotFoundException.class);
    }

    @Test 
    void getBalancesForAccountWithNoBalancesReturnsEmptyList() {
        Account account = newAccount();

        AccountBalancesResult result = accountService.getBalances(account.getAccountNumber());

        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.balances()).isEmpty();
    }

    @Test
    void successfulDebitReducesBalanceAndWritesLedgerEntry() {
        Account account = accountWith(Currency.EUR, "100.00");

        AccountBalanceResult result = accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("30.00"));

        assertThat(result.balance()).isEqualByComparingTo("70.00");
        assertThat(balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("70.00");
        List<LedgerEntry> entries = ledgerRepository.findByAccountIdOrderByIdAsc(account.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getType()).isEqualTo(EntryType.DEBIT);
        assertThat(entries.get(1).getNewBalance()).isEqualByComparingTo("70.00");
        verify(externalLoggingClient).logDebitAttempt();
    }

    @Test
    void externalFailureRejectsDebitAndChangesNothing() {
        Account account = accountWith(Currency.EUR, "100.00");
        doThrow(new ExternalSystemException("system down")).when(externalLoggingClient).logDebitAttempt();

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("30.00")))
                .isInstanceOf(ExternalSystemException.class);

        assertThat(balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).hasSize(1);
    }

    @Test
    void insufficientFundsLeavesBalanceAndLedgerUnchanged() {
        Account account = accountWith(Currency.EUR, "10.00");

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).hasSize(1);
    }

    @Test
    void debitInCurrencyTheAccountNeverHeldIsInsufficientFundsAndCreatesNoRow() {
        Account account = accountWith(Currency.EUR, "10.00");

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.USD, new BigDecimal("1.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.USD)).isEmpty();
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).hasSize(1);
    }

    @Test
    void externalCallHappensOutsideAnyTransaction() {
        Account account = accountWith(Currency.EUR, "100.00");
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
        Account account = accountWith(Currency.EUR, "100.00");

        accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("20.00"));

        List<LedgerEntry> debitEntries = ledgerRepository.findByAccountIdOrderByIdAsc(account.getId()).stream()
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

    @ParameterizedTest
    @ValueSource(strings = {"100.00", "100", "100.0"}) 
    void successfulExchangeReturnsResultWithCorrectScale(String amount) {
        Account account = accountWith(Currency.EUR, "100.00");

        ExchangeResult result = accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal(amount));

        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.amountDebited()).isEqualTo("100.00");
        assertThat(result.amountCredited()).isEqualTo("112.00");
        assertThat(result.exchangeRate()).isEqualTo("1.120000");
        assertThat(result.fromBalanceAfter()).isEqualTo("0.00");
        assertThat(result.toBalanceAfter()).isEqualTo("112.00");
    }

    @Test 
    void successfulExchangeUpdatesBalancesAndWritesLedgerRows() {
        Account account = accountWith(Currency.EUR, "100.00");

        accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"));

        AccountBalance eurBalance = balanceOf(account, Currency.EUR).orElseThrow();
        AccountBalance usdBalance = balanceOf(account, Currency.USD).orElseThrow();

        assertThat(eurBalance.getBalance()).isEqualByComparingTo("90.00");
        assertThat(usdBalance.getBalance()).isEqualByComparingTo("11.20");

        List<LedgerEntry> ledgerEntries = ledgerRepository.findByAccountIdOrderByIdAsc(account.getId());

        LedgerEntry debit = ledgerEntries.stream().filter(e -> e.getType() == EntryType.DEBIT).findFirst().orElseThrow();
        List<LedgerEntry> credits = ledgerEntries.stream().filter(e -> e.getType() == EntryType.CREDIT && e.getCorrelationId().equals(debit.getCorrelationId())).toList();
        
        assertThat(credits).hasSize(1);
        LedgerEntry credit = credits.get(0);

        assertThat(ledgerEntries).hasSize(3);
        assertThat(debit.getAmount()).isEqualByComparingTo("10.00");
        assertThat(debit.getNewBalance()).isEqualByComparingTo("90.00");
        assertThat(credit.getAmount()).isEqualByComparingTo("11.20");
        assertThat(credit.getNewBalance()).isEqualByComparingTo("11.20");

        verify(externalLoggingClient, never()).logDebitAttempt();
    }

    @Test
    void exchangeOnUnknownAccountThrows() {
        assertThatThrownBy(() -> accountService.exchange("UNKNOWN", Currency.EUR, Currency.USD, new BigDecimal("10.00")))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test 
    void exchangeFromCurrencyTheAccountNeverHeldIsInsufficientFundsAndCreatesNoBalanceRows() {
        Account account = newAccount();

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.USD, Currency.SEK, new BigDecimal("10.00")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining(account.getAccountNumber());
        
        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.USD)).isEmpty();
        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.SEK)).isEmpty();
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).isEmpty();

    }

    @Test 
    void exchangeFromCurrencyWithInsufficientFundsThrowsAndCreatesNoTargetBalanceRow() {
        Account account = accountWith(Currency.SEK, "10.00");

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.SEK, Currency.USD, new BigDecimal("10.50")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("SEK")
                .hasMessageContaining(account.getAccountNumber());
        
        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.USD)).isEmpty();
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).hasSize(1);
    }

    @Test 
    void exchangeToSameCurrencyThrows() {
        Account account = accountWith(Currency.SEK, "100.00");

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.SEK, Currency.SEK, new BigDecimal("10.00")))
                .isInstanceOf(InvalidCurrencyExchangeException.class)
                .hasMessageContaining("SEK to SEK");

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.SEK).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).hasSize(1);
    }

    @Test 
    void rejectsExchangeWhenConvertedAmountRoundsToZero() {
        Account account = accountWith(Currency.SEK, "100.00");

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.SEK, Currency.EUR, new BigDecimal("0.01")))
                .isInstanceOf(InvalidCurrencyExchangeException.class)
                .hasMessageContaining("too small");

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.SEK).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test 
    void exchangeWritesLedgerEntriesWithMatchingCorrelationIds() {
        Account account = accountWith(Currency.GBP, "100.00");
        accountService.deposit(account.getAccountNumber(), Currency.SEK, new BigDecimal("12.00"));

        accountService.exchange(account.getAccountNumber(), Currency.GBP, Currency.SEK, new BigDecimal("10.00"));

        List<LedgerEntry> ledgerEntries = ledgerRepository.findByAccountIdOrderByIdAsc(account.getId());

        LedgerEntry debit = ledgerEntries.stream().filter(e -> e.getType() == EntryType.DEBIT).findFirst().orElseThrow();
        List<LedgerEntry> credits = ledgerEntries.stream().filter(e -> e.getType() == EntryType.CREDIT && e.getCorrelationId().equals(debit.getCorrelationId())).toList();
        
        assertThat(credits).hasSize(1);
        LedgerEntry credit = credits.get(0);

        assertThat(debit.getCurrency()).isEqualTo(Currency.GBP);
        assertThat(debit.getAmount()).isEqualByComparingTo("10.00");
        assertThat(debit.getNewBalance()).isEqualByComparingTo("90.00");

        assertThat(credit.getCurrency()).isEqualTo(Currency.SEK);
        assertThat(credit.getAmount()).isEqualByComparingTo("132.35");
        assertThat(credit.getNewBalance()).isEqualByComparingTo("144.35");

        assertThat(ledgerEntries.stream().map(l -> l.getCorrelationId()).distinct().toList()).hasSize(3);
    }
}
