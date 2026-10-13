package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.ExchangeResult;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest(properties = { "exchange.rates.EUR=1.0", "exchange.rates.USD=1.12", "exchange.rates.SEK=11.25", "exchange.rates.GBP=0.85" })
class AccountServiceConcurrencyTests {
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountBalanceRepository balanceRepository;
    @Autowired LedgerEntryRepository ledgerRepository;
    @MockitoBean ExternalLoggingClient externalLoggingClient;
    private AccountTestData testData;

    @BeforeEach
    void setUp() {
        testData = new AccountTestData(accountRepository, balanceRepository, ledgerRepository, accountService);
    }

    @Test
    void parallelFirstDepositsInNewCurrencyCreateOneRowAndKeepConsistentRunningBalances() {
        Account account = testData.newAccount();

        IntStream.range(0, 20).parallel().forEach(i ->
            accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("1.00"), UUID.randomUUID()));

        List<AccountBalance> rows = balanceRepository.findByAccountId(account.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBalance()).isEqualByComparingTo("20.00");

        List<LedgerEntry> entries = testData.ledgerOf(account);
        List<Integer> runningBalances = entries.stream().map(e -> e.getNewBalance().intValue()).sorted().toList();

        List<Integer> expected = IntStream.rangeClosed(1, 20).boxed().toList();

        assertThat(runningBalances).isEqualTo(expected);
    }

    @Test
    void parallelDepositsToExistingBalanceLoseNoUpdates() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"), UUID.randomUUID());

        IntStream.range(0, 20).parallel().forEach(i ->
            accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1.00"), UUID.randomUUID()));

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("120.00");
    }

    @Test
    void parallelDepositsWithSameIdempotencyKeyDepositOnce() {
        Account account = testData.newAccount();
        UUID idempotencyKey = UUID.randomUUID();

        List<AccountBalanceResult> results = IntStream.range(0, 20).parallel()
            .mapToObj(i -> accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1.00"), idempotencyKey))
            .toList();

        assertThat(results).allSatisfy(r -> assertThat(r).isEqualTo(results.get(0)));
        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("1.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void parallelDebitsNeverOverdrawTheBalance() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID());

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        IntStream.range(0, 30).parallel().forEach(i -> {
            try {
                accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1.00"), UUID.randomUUID());
                succeeded.incrementAndGet();
            } catch (InsufficientFundsException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(20);
        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("0.00");
        assertThat(testData.ledgerOf(account)).hasSize(11);
    }

    @Test
    void parallelDebitsWithSameIdempotencyKeyDebitOnce() {
        Account account = testData.accountWith(Currency.EUR, "100.00");

        UUID idempotencyKey = UUID.randomUUID();

        List<AccountBalanceResult> results = IntStream.range(0, 20).parallel()
            .mapToObj(i -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), idempotencyKey))
            .toList();

        assertThat(results).allSatisfy(r -> assertThat(r).isEqualTo(results.get(0)));
        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("90.00");
        assertThat(testData.ledgerOf(account)).hasSize(2);
    }

    @Test 
    void parallelExchangesKeepBalancesConsistent() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1000.00"), UUID.randomUUID());
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("1000.00"), UUID.randomUUID());

        IntStream.range(0, 20).parallel().forEach(i -> {
            if (i % 2 == 0) {
                accountService.exchange(
                    account.getAccountNumber(),
                    Currency.EUR,
                    Currency.USD,
                    new BigDecimal("10.00"),
                    UUID.randomUUID()
                );
            } else {
                accountService.exchange(
                    account.getAccountNumber(),
                    Currency.USD,
                    Currency.EUR,
                    new BigDecimal("1.00"),
                    UUID.randomUUID()
                );
            }
        });

        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("908.90");
        assertThat(testData.balanceOf(account, Currency.USD)
                .orElseThrow().getBalance()).isEqualByComparingTo("1102.00");
        assertThat(testData.ledgerOf(account)).hasSize(42);
    }

    @Test
    void parallelExchangesWithSameIdempotencyKeyExchangeOnce() {
        Account account = testData.accountWith(Currency.EUR, "100.00");

        UUID idempotencyKey = UUID.randomUUID();

        List<ExchangeResult> results = IntStream.range(0, 20).parallel()
            .mapToObj(i -> accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey))
            .toList();

        assertThat(results).allSatisfy(r -> assertThat(r).isEqualTo(results.get(0)));
        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("90.00");
        assertThat(testData.balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("11.20");
        assertThat(testData.ledgerOf(account)).hasSize(3);
    }
}
