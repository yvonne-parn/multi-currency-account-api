package ee.yvonne.bank.idempotency;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.common.IdempotencyConflictException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class IdempotencyService {
    private final IdempotencyRecordRepository repository;

    public Optional<IdempotencyRecord> findDuplicate(Long accountId, UUID idempotencyKey, IdempotencyRequestContent requestContent) {
        return repository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
                .map(record -> {
                    if (!record.hasSameContent(requestContent)) {
                        throw new IdempotencyConflictException(idempotencyKey);
                    }
                    return record;
                });
    }

    public void save(Account account, UUID idempotencyKey, IdempotencyRequestContent requestContent, UUID correlationId) {
        repository.save(new IdempotencyRecord(account, idempotencyKey, requestContent, correlationId));
    }
}
