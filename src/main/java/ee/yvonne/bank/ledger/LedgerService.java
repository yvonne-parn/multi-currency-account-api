package ee.yvonne.bank.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.common.Currency;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class LedgerService {
    private final LedgerEntryRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Account account, Currency currency, EntryType type, BigDecimal amount, BigDecimal newBalance, UUID correlationId) {
        repository.save(new LedgerEntry(account, currency, type, amount, newBalance, correlationId));
    }

    public List<LedgerEntry> findEntries(UUID correlationId) {
        return repository.findByCorrelationId(correlationId);
    }

    public LedgerEntry findEntry(UUID correlationId, EntryType type) {
        return entryOfType(findEntries(correlationId), type, correlationId);
    }

    public static LedgerEntry entryOfType(List<LedgerEntry> entries, EntryType type, UUID correlationId) {
        return entries.stream()
                .filter(e -> e.getType() == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Ledger entry of type " + type + " missing for correlation id " + correlationId));
    }
}
