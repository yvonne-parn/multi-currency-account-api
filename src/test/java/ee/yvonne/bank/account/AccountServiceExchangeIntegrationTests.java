package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.yvonne.bank.account.dto.ExchangeResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.IdempotencyConflictException;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.common.InvalidCurrencyExchangeException;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest(properties = { "exchange.rates.EUR=1.0", "exchange.rates.USD=1.12", "exchange.rates.SEK=11.25", "exchange.rates.GBP=0.85" })
class AccountServiceExchangeIntegrationTests {
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

    @ParameterizedTest
    @ValueSource(strings = {"100.00", "100", "100.0"}) 
    void successfulExchangeReturnsResultWithCorrectScale(String amount) {
        Account account = testData.accountWith(Currency.EUR, "100.00");

        ExchangeResult result = accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal(amount), UUID.randomUUID());

        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.amountDebited()).isEqualTo("100.00");
        assertThat(result.amountCredited()).isEqualTo("112.00");
        assertThat(result.exchangeRate()).isEqualTo("1.120000");
        assertThat(result.fromBalanceAfter()).isEqualTo("0.00");
        assertThat(result.toBalanceAfter()).isEqualTo("112.00");
    }

    @Test 
    void successfulExchangeUpdatesBalancesAndWritesLedgerRows() {
        Account account = testData.accountWith(Currency.EUR, "100.00");

        accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), UUID.randomUUID());

        AccountBalance eurBalance = testData.balanceOf(account, Currency.EUR).orElseThrow();
        AccountBalance usdBalance = testData.balanceOf(account, Currency.USD).orElseThrow();

        assertThat(eurBalance.getBalance()).isEqualByComparingTo("90.00");
        assertThat(usdBalance.getBalance()).isEqualByComparingTo("11.20");

        List<LedgerEntry> ledgerEntries = testData.ledgerOf(account);

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
        assertThatThrownBy(() -> accountService.exchange("UNKNOWN", Currency.EUR, Currency.USD, new BigDecimal("10.00"), UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test 
    void exchangeFromCurrencyTheAccountNeverHeldIsInsufficientFundsAndCreatesNoBalanceRows() {
        Account account = testData.newAccount();

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.USD, Currency.SEK, new BigDecimal("10.00"), UUID.randomUUID()))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining(account.getAccountNumber());
         
        assertThat(testData.balanceOf(account, Currency.USD)).isEmpty();
        assertThat(testData.balanceOf(account, Currency.SEK)).isEmpty();
        assertThat(testData.ledgerOf(account)).isEmpty();
    }

    @Test 
    void exchangeFromCurrencyWithInsufficientFundsThrowsAndCreatesNoTargetBalanceRow() {
        Account account = testData.accountWith(Currency.SEK, "10.00");

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.SEK, Currency.USD, new BigDecimal("10.50"), UUID.randomUUID()))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("SEK")
                .hasMessageContaining(account.getAccountNumber());
        
        assertThat(testData.balanceOf(account, Currency.USD)).isEmpty();
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test 
    void exchangeToSameCurrencyThrows() {
        Account account = testData.accountWith(Currency.SEK, "100.00");

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.SEK, Currency.SEK, new BigDecimal("10.00"), UUID.randomUUID()))
                .isInstanceOf(InvalidCurrencyExchangeException.class)
                .hasMessageContaining("SEK to SEK");

        assertThat(testData.balanceOf(account, Currency.SEK).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test 
    void rejectsExchangeWhenConvertedAmountRoundsToZero() {
        Account account = testData.accountWith(Currency.SEK, "100.00");

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.SEK, Currency.EUR, new BigDecimal("0.01"), UUID.randomUUID()))
                .isInstanceOf(InvalidCurrencyExchangeException.class)
                .hasMessageContaining("too small");

        assertThat(testData.balanceOf(account, Currency.SEK).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test 
    void exchangeWritesLedgerEntriesWithMatchingCorrelationIds() {
        Account account = testData.accountWith(Currency.GBP, "100.00");
        accountService.deposit(account.getAccountNumber(), Currency.SEK, new BigDecimal("12.00"), UUID.randomUUID());

        accountService.exchange(account.getAccountNumber(), Currency.GBP, Currency.SEK, new BigDecimal("10.00"), UUID.randomUUID());

        List<LedgerEntry> ledgerEntries = testData.ledgerOf(account);

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

    @Test
    void sameIdempotencyKeySentTwicePerformsExchangeOnce() {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        UUID idempotencyKey = UUID.randomUUID();

        ExchangeResult first = accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey);
        ExchangeResult second = accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey);

        assertThat(second).isEqualTo(first);
        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("90.00");
        assertThat(testData.balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("11.20");
        assertThat(testData.ledgerOf(account)).hasSize(3);
    }

    @Test 
    void duplicateExchangeReturnsOriginalBalancesAfterLaterActivity() {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        UUID idempotencyKey = UUID.randomUUID();

        ExchangeResult first = accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey);
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), UUID.randomUUID());
        ExchangeResult duplicate = accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey);

        assertThat(duplicate).isEqualTo(first);
        assertThat(duplicate.fromBalanceAfter()).isEqualByComparingTo("90.00");
        assertThat(duplicate.toBalanceAfter()).isEqualByComparingTo("11.20");
        assertThat(duplicate.amountCredited()).isEqualByComparingTo("11.20");
        assertThat(duplicate.amountDebited()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(4);
    }

    @Test 
    void sameKeyWithDifferentTargetCurrencyThrowsIdempotencyConflict() {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        UUID idempotencyKey = UUID.randomUUID();

        accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey);
        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.SEK, new BigDecimal("10.00"), idempotencyKey))
            .isInstanceOf(IdempotencyConflictException.class)
            .hasMessageContaining(idempotencyKey.toString());

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("90.00");
        assertThat(testData.ledgerOf(account)).hasSize(3);
    }
}
