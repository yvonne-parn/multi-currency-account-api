package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.ExchangeResult;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.ExternalSystemException;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.idempotency.IdempotencyRecordRepository;
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
    @Autowired IdempotencyRecordRepository idempotencyRecordRepository;
    private AccountTestData testData;

    @BeforeEach
    void setUp() {
        testData = new AccountTestData(accountRepository, balanceRepository, ledgerRepository, accountService);
    }

    @Test
    void ledgerFailureLeavesExistingBalanceUnchanged() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID());

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void ledgerFailureOnDepositCreatesNoIdempotencyRecordAndRetrySucceeds() {
        Account account = testData.newAccount();
        UUID idempotencyKey = UUID.randomUUID();

        doThrow(new DataAccessResourceFailureException("ledger down"))
                .when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isNotPresent();

        Mockito.reset(ledgerRepository); 

        AccountBalanceResult retry = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey);

        assertThat(retry.balance()).isEqualByComparingTo("5.00");
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isPresent();
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void ledgerFailureOnFirstDepositCreatesNoBalanceRow() {
        Account account = testData.newAccount();

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("50.00"), UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.USD)).isEmpty();
        assertThat(testData.ledgerOf(account)).isEmpty();
    }

    @Test
    void ledgerFailureDuringDebitLeavesBalanceUnchanged() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"), UUID.randomUUID());

        doThrow(new DataAccessResourceFailureException("ledger down")).when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("30.00"), UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void debitFailureCreatesNoIdempotencyRecordAndRetrySucceeds() {
        Account account = testData.accountWith(Currency.EUR, "1.00");
        UUID idempotencyKey = UUID.randomUUID();

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isNotPresent();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID());
        AccountBalanceResult retry = accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey);

        assertThat(retry.balance()).isEqualByComparingTo("6.00");
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isPresent();
        assertThat(testData.ledgerOf(account)).hasSize(3);
    }

    @Test
    void externalClientFailureCreatesNoIdempotencyRecord() {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        UUID idempotencyKey = UUID.randomUUID();

        doThrow(new ExternalSystemException("external service down")).when(externalLoggingClient).logDebitAttempt();

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey))
                .isInstanceOf(ExternalSystemException.class);
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isNotPresent();

        Mockito.reset(externalLoggingClient);
        AccountBalanceResult retry = accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey);

        assertThat(retry.balance()).isEqualByComparingTo("95.00");
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isPresent();
        assertThat(testData.ledgerOf(account)).hasSize(2);
        verify(externalLoggingClient, times(1)).logDebitAttempt();
    }

    @Test
    void ledgerFailureOnDebitCreatesNoIdempotencyRecordAndRetrySucceeds() {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        UUID idempotencyKey = UUID.randomUUID();

        doThrow(new DataAccessResourceFailureException("ledger down"))
                .when(ledgerRepository).save(any(LedgerEntry.class));

        assertThatThrownBy(() -> accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isNotPresent();

        Mockito.reset(ledgerRepository); 

        AccountBalanceResult retry = accountService.debit(account.getAccountNumber(), Currency.EUR, new BigDecimal("5.00"), idempotencyKey);

        assertThat(retry.balance()).isEqualByComparingTo("95.00");
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isPresent();
        assertThat(testData.ledgerOf(account)).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(EntryType.class)
    void ledgerFailureDuringExchangeLeavesBalancesUnchanged(EntryType entryType) {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"), UUID.randomUUID());

        doThrow(new DataAccessResourceFailureException("ledger down"))
            .when(ledgerRepository)
            .save(argThat((LedgerEntry entry) ->
                entry.getType() == entryType
            ));

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.balanceOf(account, Currency.USD).isPresent()).isFalse();
        assertThat(testData.ledgerOf(account)).hasSize(1);
    }

    @Test
    void ledgerFailureDuringExchangeLeavesExistingTargetBalanceUnchanged() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("100.00"), UUID.randomUUID());
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("10.00"), UUID.randomUUID());

        doThrow(new DataAccessResourceFailureException("ledger down"))
                .when(ledgerRepository)
                .save(argThat((LedgerEntry entry) -> entry.getType() == EntryType.CREDIT));

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(testData.balanceOf(account, Currency.EUR)
                .orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.balanceOf(account, Currency.USD)
                .orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.ledgerOf(account)).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(EntryType.class)
    void ledgerFailureDuringExchangeCreatesNoIdempotencyRecordAndRetrySucceeds(EntryType type) {
        Account account = testData.accountWith(Currency.EUR, "100.00");
        UUID idempotencyKey = UUID.randomUUID();

        doThrow(new DataAccessResourceFailureException("ledger down"))
                .when(ledgerRepository)
                .save(argThat((LedgerEntry entry) -> entry.getType() == type));

        assertThatThrownBy(() -> accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isNotPresent();
        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(testData.balanceOf(account, Currency.USD)).isEmpty();
        assertThat(testData.ledgerOf(account)).hasSize(1);

        Mockito.reset(ledgerRepository); 

        ExchangeResult retry = accountService.exchange(account.getAccountNumber(), Currency.EUR, Currency.USD, new BigDecimal("10.00"), idempotencyKey);

        assertThat(retry.fromBalanceAfter()).isEqualByComparingTo("90.00");
        assertThat(retry.toBalanceAfter()).isEqualByComparingTo("11.20");
        assertThat(idempotencyRecordRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isPresent();
        assertThat(testData.ledgerOf(account)).hasSize(3);
    }
}
