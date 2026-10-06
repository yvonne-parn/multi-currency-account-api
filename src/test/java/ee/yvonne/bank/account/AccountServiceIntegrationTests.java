package ee.yvonne.bank.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
class AccountServiceIntegrationTests {
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountBalanceRepository balanceRepository;
    @Autowired private LedgerEntryRepository ledgerRepository;

    private Account newAccount() {
        return accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
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

        List<LedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId());
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
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).isEmpty();
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
        Account first = newAccount();
        Account second = newAccount();
        accountService.deposit(first.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.deposit(second.getAccountNumber(), Currency.USD, new BigDecimal("99.00"));

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
}
