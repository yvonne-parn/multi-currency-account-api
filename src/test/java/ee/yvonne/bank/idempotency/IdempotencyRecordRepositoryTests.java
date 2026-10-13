package ee.yvonne.bank.idempotency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.account.AccountRepository;
import ee.yvonne.bank.common.Currency;

@DataJpaTest
class IdempotencyRecordRepositoryTests {
    @Autowired private IdempotencyRecordRepository idempotencyRepository;
    @Autowired AccountRepository accountRepository;
    
    @Test
    void secondIdempotencyRecordForSameAccountAndKeyIsRejected() {
        Account account = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));

        UUID idempotencyKey = UUID.randomUUID();
        idempotencyRepository.saveAndFlush(new IdempotencyRecord(account, idempotencyKey, new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, Operation.DEBIT), UUID.randomUUID()));

        assertThatThrownBy(() -> idempotencyRepository.saveAndFlush(new IdempotencyRecord(account, idempotencyKey,new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.USD, null, Operation.DEBIT), UUID.randomUUID())))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyOnTwoDifferentAccountsIsAccepted() {
        Account account = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
        Account other = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder 2"));

        UUID idempotencyKey = UUID.randomUUID();
        idempotencyRepository.saveAndFlush(new IdempotencyRecord(account, idempotencyKey, new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, Operation.DEPOSIT), UUID.randomUUID()));
        idempotencyRepository.saveAndFlush(new IdempotencyRecord(other, idempotencyKey, new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, Operation.DEPOSIT), UUID.randomUUID()));
        
        assertThat(idempotencyRepository.findByAccountIdAndIdempotencyKey(account.getId(), idempotencyKey)).isPresent();
        assertThat(idempotencyRepository.findByAccountIdAndIdempotencyKey(other.getId(), idempotencyKey)).isPresent();
    }

    @Test
    void finderReturnsOnlyTheRecordOfThatAccountAndKey() {
        Account account = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
        Account other = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder 2"));
        UUID key = UUID.randomUUID();

        idempotencyRepository.saveAndFlush(new IdempotencyRecord(account, key, new IdempotencyRequestContent(new BigDecimal("10.00"), Currency.EUR, null, Operation.DEBIT), UUID.randomUUID()));

        assertThat(idempotencyRepository.findByAccountIdAndIdempotencyKey(account.getId(), key)).isPresent();
        assertThat(idempotencyRepository.findByAccountIdAndIdempotencyKey(other.getId(), key)).isEmpty();
        assertThat(idempotencyRepository.findByAccountIdAndIdempotencyKey(account.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void deleteCreatedBeforeRemovesOnlyOlderRecords() throws InterruptedException {
        Account account = accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
        UUID oldKey = UUID.randomUUID();
        UUID newKey = UUID.randomUUID();

        idempotencyRepository.saveAndFlush(new IdempotencyRecord(account, oldKey, IdempotencyRequestContent.deposit(new BigDecimal("1.00"), Currency.EUR), UUID.randomUUID()));
        Thread.sleep(20);
        Instant cutoff = Instant.now();
        Thread.sleep(20);
        idempotencyRepository.saveAndFlush(new IdempotencyRecord(account, newKey, IdempotencyRequestContent.deposit(new BigDecimal("2.00"), Currency.EUR), UUID.randomUUID()));

        int deleted = idempotencyRepository.deleteCreatedBefore(cutoff);

        assertThat(deleted).isEqualTo(1);
        assertThat(idempotencyRepository.findByAccountIdAndIdempotencyKey(account.getId(), oldKey)).isEmpty();
        assertThat(idempotencyRepository.findByAccountIdAndIdempotencyKey(account.getId(), newKey)).isPresent();
    }
}
