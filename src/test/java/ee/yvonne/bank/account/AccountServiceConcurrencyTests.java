package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

    private Account newAccount() {
        return accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
    }

    @Test
    void parallelFirstDepositsInNewCurrencyCreateOneRowAndKeepConsistentRunningBalances() {
        Account account = newAccount();

        IntStream.range(0, 20).parallel().forEach(i ->
            accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("1.00")));

        List<AccountBalance> rows = balanceRepository.findByAccountId(account.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBalance()).isEqualByComparingTo("20.00");

        List<LedgerEntry> entries = ledgerRepository.findByAccountIdOrderByIdAsc(account.getId());
        List<Integer> runningBalances = entries.stream().map(e -> e.getNewBalance().intValue()).sorted().toList();

        List<Integer> expected = IntStream.rangeClosed(1, 20).boxed().toList();

        assertThat(runningBalances).isEqualTo(expected);
    }

    @Test
    void parallelDepositsToExistingBalanceLoseNoUpdates() {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"));

        IntStream.range(0, 20).parallel().forEach(i ->
            accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1.00")));

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("120.00");
    }

    @Test
    void parallelDebitsNeverOverdrawTheBalance() {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        IntStream.range(0, 30).parallel().forEach(i -> {
            try {
                accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1.00"));
                succeeded.incrementAndGet();
            } catch (InsufficientFundsException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(20);
        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("0.00");
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).hasSize(11);
    }

    @Test 
    void parallelExchangesKeepBalancesConsistent() {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("1000.00"));
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("1000.00"));

        IntStream.range(0, 20).parallel().forEach(i -> {
            if (i % 2 == 0) {
                accountService.exchange(
                    account.getAccountNumber(),
                    Currency.EUR,
                    Currency.USD,
                    new BigDecimal("10.00")
                );
            } else {
                accountService.exchange(
                    account.getAccountNumber(),
                    Currency.USD,
                    Currency.EUR,
                    new BigDecimal("1.00")
                );
            }
        });

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("908.90");
                assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.USD)
                .orElseThrow().getBalance()).isEqualByComparingTo("1102.00");
        assertThat(ledgerRepository.findByAccountIdOrderByIdAsc(account.getId())).hasSize(42);
    }
}
