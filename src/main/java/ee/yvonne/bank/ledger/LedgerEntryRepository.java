package ee.yvonne.bank.ledger;

import java.util.List;

import org.springframework.data.repository.Repository;

public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {
    LedgerEntry save(LedgerEntry transaction);
    List<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(Long accountId);
}
