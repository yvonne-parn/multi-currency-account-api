package ee.yvonne.bank.idempotency;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class IdempotencyCleanupJob {
    private final IdempotencyService idempotencyService;
    private final Duration retention;

    public IdempotencyCleanupJob(IdempotencyService idempotencyService, @Value("${idempotency.retention}") Duration retention) {
        this.idempotencyService = idempotencyService;
        this.retention = retention;
    }

    @Scheduled(cron = "${idempotency.cleanup-interval}")
    void cleanUp() {
        int deleted = idempotencyService.deleteExpired(Instant.now().minus(retention));
        if (deleted > 0) {
            log.info("Deleted {} expired idempotency records", deleted);
        }
    }
}
