package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.IdempotencyConflictException;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.idempotency.IdempotencyService;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest(properties = { "exchange.rates.EUR=1.0", "exchange.rates.USD=1.12", "exchange.rates.SEK=11.25", "exchange.rates.GBP=0.85" })
class AccountServiceDepositIntegrationTests {
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountBalanceRepository balanceRepository;
    @Autowired private LedgerEntryRepository ledgerRepository;
    @Autowired private IdempotencyService idempotencyService;
    @MockitoBean ExternalLoggingClient externalLoggingClient;
    private AccountTestData testData;

    @BeforeEach
    void setUp() {
        testData = new AccountTestData(accountRepository, balanceRepository, ledgerRepository, accountService);
    }

    @Test
    void firstDepositInNewCurrencyCreatesBalanceRow() {
        Account account = testData.newAccount();

        AccountBalanceResult result = accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("100.00"), UUID.randomUUID());

        assertThat(result.balance()).isEqualByComparingTo("100.00");
        assertThat(testData.balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void depositInExistingCurrencyAddsToBalance() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID());

        AccountBalanceResult result = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("25.00"), UUID.randomUUID());

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("35.00");
        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.currency()).isEqualTo(Currency.EUR);
        assertThat(result.balance()).isEqualByComparingTo("35.00");
    }

    @Test
    void depositsInDifferentCurrenciesStaySeparate() {
        Account account = testData.newAccount();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID());
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("20.00"), UUID.randomUUID());

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
        assertThat(testData.balanceOf(account, Currency.SEK)).isEmpty();
    }

    @Test
    void depositToUnknownAccountThrows() {
        assertThatThrownBy(() -> accountService.deposit("NON-EXISTENT", Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID())).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void depositWritesLedgerEntryWithRunningBalance() {
        Account account = testData.newAccount();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID());
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("20.00"), UUID.randomUUID());

        List<LedgerEntry> entries = testData.ledgerOf(account);
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
        Account account = testData.newAccount();

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.GBP, new BigDecimal("-5.00"), UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);

        assertThat(testData.balanceOf(account, Currency.GBP)).isEmpty();
        assertThat(testData.ledgerOf(account)).isEmpty();
    }

    @Test
    void sameIdempotencyKeySentTwiceMovesMoneyOnce() {
        Account account = testData.newAccount();
        UUID idempotencyKey = UUID.randomUUID();

        AccountBalanceResult first = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey);
        AccountBalanceResult second = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey);

        assertThat(second).isEqualTo(first);
        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance())
                .isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test 
    void duplicateRequestWithSameContentReturnsSameResult() {
        Account account = testData.newAccount();
        UUID idempotencyKey = UUID.randomUUID();

        AccountBalanceResult first = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey);
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), UUID.randomUUID());
        AccountBalanceResult duplicate = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey);

        assertThat(duplicate).isEqualTo(first);
        assertThat(duplicate.balance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(2);
    }

    @ParameterizedTest 
    @CsvSource({"USD, 10.00", "EUR, 10.05"}) 
    void duplicateRequestWithDifferentContentThrows(String currencyCode, String amountString) {
        Account account = testData.newAccount();
        UUID idempotencyKey = UUID.randomUUID();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey);
        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.valueOf(currencyCode), new BigDecimal(amountString), idempotencyKey))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining(idempotencyKey.toString());

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test 
    void sameIdempotencyKeyOnDifferentAccountsMovesMoneyForBoth() {
        Account account = testData.newAccount();
        Account other = testData.newAccount();
        UUID idempotencyKey = UUID.randomUUID();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey);
        accountService.deposit(other.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey);

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.balanceOf(other, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
        assertThat(testData.ledgerOf(other)).hasSize(1);
    }

    @Test
    void idempotencyKeyIsForgottenAfterExpiryAndTheRetryIsANewRequest() {
        Account account = testData.newAccount();
        UUID key = UUID.randomUUID();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), key);

        idempotencyService.deleteExpired(Instant.now().plusSeconds(60));

        AccountBalanceResult retry = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), key);
        assertThat(retry.balance()).isEqualByComparingTo("20.00");
    }

    @Test
    void idempotencyKeyWithinRetentionStillDeduplicates() {
        Account account = testData.newAccount();
        UUID key = UUID.randomUUID();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), key);

        int deleted = idempotencyService.deleteExpired(Instant.now().minus(Duration.ofHours(1)));
        assertThat(deleted).isEqualTo(0);

        AccountBalanceResult retry = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), key);
        assertThat(retry.balance()).isEqualByComparingTo("10.00");
    }
}
