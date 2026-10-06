package ee.yvonne.bank.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.common.Currency;

@Service
public class LedgerService {
    private final LedgerEntryRepository repository;

    public LedgerService(LedgerEntryRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Account account, Currency currency, EntryType type, BigDecimal amount, BigDecimal newBalance, UUID correlationId) {
        repository.save(new LedgerEntry(account, currency, type, amount, newBalance, correlationId));
    }
}
