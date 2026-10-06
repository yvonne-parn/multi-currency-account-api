package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import ee.yvonne.bank.common.Currency;

@DataJpaTest
class AccountBalanceRepositoryTests {
    @Autowired AccountRepository accountRepository;
    @Autowired AccountBalanceRepository balanceRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void secondBalanceRowForSameAccountAndCurrencyIsRejected() {
        Account account = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
        balanceRepository.saveAndFlush(new AccountBalance(account, Currency.EUR));

        assertThatThrownBy(() -> balanceRepository.saveAndFlush(new AccountBalance(account, Currency.EUR)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativeBalance() {
        Account account = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
        AccountBalance balance = balanceRepository.saveAndFlush(new AccountBalance(account, Currency.EUR));

        assertThatThrownBy(() ->
                jdbcTemplate.update("update account_balance set balance = -1.00 where id = ?", balance.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
