package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest
class AccountServiceRollbackTests {
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired AccountBalanceRepository balanceRepository;
    @MockitoSpyBean LedgerEntryRepository ledgerRepository;
    @MockitoBean ExternalLoggingClient externalLoggingClient;

    private Account newAccount() {
        return accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
    }

    @Test
    void ledgerFailureLeavesExistingBalanceUnchanged() {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).hasSize(1);
    }

    @Test
    void ledgerFailureOnFirstDepositCreatesNoBalanceRow() {
        Account account = newAccount();

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("50.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.USD)).isEmpty();
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).isEmpty();
    }

    @Test
    void ledgerFailureDuringDebitLeavesBalanceUnchanged() {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"));

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("30.00")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(balanceRepository.findByAccountIdAndCurrency(account.getId(), Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(ledgerRepository.findByAccountIdOrderByCreatedAtAsc(account.getId())).hasSize(1);
    }
}
