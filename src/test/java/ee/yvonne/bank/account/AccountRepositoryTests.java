package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class AccountRepositoryTests {
    @Autowired private AccountRepository accountRepository;

    @Test
    void secondAccountWithSameNumberIsRejected() {
        String number = "TEST-" + UUID.randomUUID();
        accountRepository.saveAndFlush(new Account(number, "First Holder"));

        assertThatThrownBy(() -> accountRepository.saveAndFlush(new Account(number, "Second Holder")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
