package ee.yvonne.bank.ledger;

import java.util.List;

import org.springframework.data.repository.Repository;
import java.util.UUID;


public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {
    LedgerEntry save(LedgerEntry entry);
    List<LedgerEntry> findByAccountIdOrderByIdAsc(Long accountId);
    List<LedgerEntry> findByCorrelationId(UUID correlationId);
}
