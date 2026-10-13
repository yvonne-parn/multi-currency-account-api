package ee.yvonne.bank.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.IdempotencyConflictException;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTests {
    @Mock IdempotencyRecordRepository repository;
    IdempotencyService service;

    private final Account account = new Account("TEST-1", "Holder");
    private final UUID idempotencyKey = UUID.randomUUID();
    private final IdempotencyRequestContent content = IdempotencyRequestContent.deposit(new BigDecimal("10.00"), Currency.EUR);

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository);
    }

    @Test
    void findDuplicateReturnsEmptyWhenKeyIsUnknown() {
        when(repository.findByAccountIdAndIdempotencyKey(1L, idempotencyKey)).thenReturn(Optional.empty());

       assertThat(service.findDuplicate(1L, idempotencyKey, content)).isEmpty();
    }

    @Test
    void findDuplicateReturnsRecordWhenContentMatches() {
        IdempotencyRecord record = new IdempotencyRecord(account, idempotencyKey, content, UUID.randomUUID());
        when(repository.findByAccountIdAndIdempotencyKey(1L, idempotencyKey)).thenReturn(Optional.of(record));

        assertThat(service.findDuplicate(1L, idempotencyKey, content)).containsSame(record);
    }

    @Test
    void findDuplicateThrowsConflictWhenContentDiffers() {
        IdempotencyRecord record = new IdempotencyRecord(account, idempotencyKey, content, UUID.randomUUID());
        when(repository.findByAccountIdAndIdempotencyKey(1L, idempotencyKey)).thenReturn(Optional.of(record));
        IdempotencyRequestContent other = IdempotencyRequestContent.deposit(new BigDecimal("99.00"), Currency.EUR);

        assertThatThrownBy(() -> service.findDuplicate(1L, idempotencyKey, other))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining(idempotencyKey.toString());
    }

    @Test
    void saveStoresRecordWithTheGivenValues() {
        UUID correlationId = UUID.randomUUID();

        service.save(account, idempotencyKey, content, correlationId);

        verify(repository).save(argThat(record ->
           record.getIdempotencyKey().equals(idempotencyKey)
           && record.getCorrelationId().equals(correlationId)
           && record.hasSameContent(content)));
    }

    @Test
    void deleteExpiredPassesTheCutoffToTheRepositoryAndReturnsTheCount() {
        Instant cutoff = Instant.now();
        when(repository.deleteCreatedBefore(cutoff)).thenReturn(3);

        assertThat(service.deleteExpired(cutoff)).isEqualTo(3);
    }
}
