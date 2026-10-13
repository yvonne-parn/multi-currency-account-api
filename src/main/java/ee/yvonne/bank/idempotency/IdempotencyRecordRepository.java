package ee.yvonne.bank.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;


public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByAccountIdAndIdempotencyKey(Long accountId, UUID key);
    boolean existsByIdempotencyKey(UUID idempotencyKey);
}
