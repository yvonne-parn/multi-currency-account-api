package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest(properties = { "exchange.rates.EUR=1.0", "exchange.rates.USD=1.12", "exchange.rates.SEK=11.25", "exchange.rates.GBP=0.85" })
class AccountServiceDepositIntegrationTests {
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
    void firstDepositInNewCurrencyCreatesBalanceRow() {
        Account account = testData.newAccount();

        AccountBalanceResult result = accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("100.00"));

        assertThat(result.balance()).isEqualByComparingTo("100.00");
        assertThat(testData.balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void depositInExistingCurrencyAddsToBalance() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));

        AccountBalanceResult result = accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("25.00"));

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("35.00");
        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.currency()).isEqualTo(Currency.EUR);
        assertThat(result.balance()).isEqualByComparingTo("35.00");
    }

    @Test
    void depositsInDifferentCurrenciesStaySeparate() {
        Account account = testData.newAccount();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("20.00"));

        assertThat(testData.balanceOf(account, Currency.EUR).orElseThrow().getBalance()).isEqualByComparingTo("10.00");
        assertThat(testData.balanceOf(account, Currency.USD).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
        assertThat(testData.balanceOf(account, Currency.SEK)).isEmpty();
    }

    @Test
    void depositToUnknownAccountThrows() {
        assertThatThrownBy(() -> accountService.deposit("NON-EXISTENT", Currency.EUR, new BigDecimal("10.00"))).isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void depositWritesLedgerEntryWithRunningBalance() {
        Account account = testData.newAccount();

        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"));
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("20.00"));

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

        assertThatThrownBy(() -> accountService.deposit(account.getAccountNumber(), Currency.GBP, new BigDecimal("-5.00"))).isInstanceOf(IllegalArgumentException.class);

        assertThat(testData.balanceOf(account, Currency.GBP)).isEmpty();
        assertThat(testData.ledgerOf(account)).isEmpty();
    }
}
