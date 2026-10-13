package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

@SpringBootTest(properties = { "exchange.rates.EUR=1.0", "exchange.rates.USD=1.12", "exchange.rates.SEK=11.25", "exchange.rates.GBP=0.85" })
class AccountServiceBalanceIntegrationTests {
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
    void getBalancesReturnsCorrectResult() {
        Account account = testData.newAccount();
        accountService.deposit(account.getAccountNumber(), Currency.EUR, new BigDecimal("10.00"), UUID.randomUUID());
        accountService.deposit(account.getAccountNumber(), Currency.USD, new BigDecimal("20.00"), UUID.randomUUID());

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
        Account first = testData.accountWith(Currency.EUR, "10.00");
        testData.accountWith(Currency.USD, "99.00");

        AccountBalancesResult result = accountService.getBalances(first.getAccountNumber());

        assertThat(result.balances()).extracting(CurrencyBalance::currency).containsExactly(Currency.EUR);
    }

    @Test 
    void getBalancesForUnknownAccountThrows() {
        assertThatThrownBy(() -> accountService.getBalances("NON-EXISTENT")).isInstanceOf(AccountNotFoundException.class);
    }

    @Test 
    void getBalancesForAccountWithNoBalancesReturnsEmptyList() {
        Account account = testData.newAccount();

        AccountBalancesResult result = accountService.getBalances(account.getAccountNumber());

        assertThat(result.accountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(result.balances()).isEmpty();
    }
}
