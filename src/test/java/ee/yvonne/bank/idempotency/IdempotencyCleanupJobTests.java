package ee.yvonne.bank.idempotency;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class IdempotencyCleanupJobTests {

    @Test
    void cleanUpDeletesRecordsOlderThanTheRetention() {
        IdempotencyService service = mock(IdempotencyService.class);
        IdempotencyCleanupJob job = new IdempotencyCleanupJob(service, Duration.ofHours(24));
        Instant before = Instant.now();

        job.cleanUp();

        Instant after = Instant.now();

        verify(service).deleteExpired(argThat(cutoff ->
           !cutoff.isBefore(before.minus(Duration.ofHours(24))) && 
           !cutoff.isAfter(after.minus(Duration.ofHours(24)))));
    }
}
