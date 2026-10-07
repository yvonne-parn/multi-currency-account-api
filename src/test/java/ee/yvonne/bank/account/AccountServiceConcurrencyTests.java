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

@SpringBootTest
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

        List<LedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId());
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
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).hasSize(11);
    }
}
