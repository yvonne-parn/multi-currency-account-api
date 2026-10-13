package ee.yvonne.bank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.account.AccountRepository;
import ee.yvonne.bank.common.Currency;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class LedgerServiceIntegrationTests {
    @Autowired private LedgerService ledgerService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerEntryRepository entryRepository;

    private Account newAccount() {
        return accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
    }

    @Test 
    @Transactional
    void recordSavesNewLedgerRecord() {
        Account account = newAccount();
        UUID correlationId = UUID.randomUUID();
        ledgerService.record(account, Currency.EUR, EntryType.CREDIT,
                new BigDecimal("10.00"), new BigDecimal("20.00"), correlationId);

        List<LedgerEntry> entries = entryRepository.findByAccountIdOrderByIdAsc(account.getId());
        assertThat(entries).hasSize(1);
        LedgerEntry entry = entries.get(0);
        assertThat(entry.getCurrency()).isEqualTo(Currency.EUR);
        assertThat(entry.getType()).isEqualTo(EntryType.CREDIT);
        assertThat(entry.getAmount()).isEqualByComparingTo("10.00");
        assertThat(entry.getNewBalance()).isEqualByComparingTo("20.00");
        assertThat(entry.getCorrelationId()).isEqualTo(correlationId);
        assertThat(entry.getCreatedAt()).isNotNull();
    }

    @Test
    void recordWithoutAnActiveTransactionIsRejected() {
        Account account = newAccount();

        assertThatThrownBy(() -> ledgerService.record(account, Currency.EUR, EntryType.CREDIT,
                new BigDecimal("10.00"), new BigDecimal("20.00"), UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(entryRepository.findByAccountIdOrderByIdAsc(account.getId())).isEmpty();
    }

    @Test
    void entryOfTypeReturnsTheEntryOfTheRequestedType() {
        UUID correlationId = UUID.randomUUID();
        Account account = newAccount();
        LedgerEntry credit = new LedgerEntry(account, Currency.EUR, EntryType.CREDIT, new BigDecimal("10.00"), new BigDecimal("10.00"), correlationId);
        LedgerEntry debit = new LedgerEntry(account, Currency.EUR, EntryType.DEBIT, new BigDecimal("5.00"), new BigDecimal("5.00"), correlationId);

        assertThat(LedgerService.entryOfType(List.of(credit, debit), EntryType.DEBIT, correlationId)).isSameAs(debit);
        assertThat(LedgerService.entryOfType(List.of(credit, debit), EntryType.CREDIT, correlationId)).isSameAs(credit);
    }

    @Test
    void entryOfTypeThrowsWhenTheRequestedTypeIsMissing() {
        UUID correlationId = UUID.randomUUID();

        assertThatThrownBy(() -> LedgerService.entryOfType(List.of(), EntryType.DEBIT, correlationId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEBIT")
                .hasMessageContaining(correlationId.toString());
    }

    @Test
    @Transactional
    void findEntriesReturnsOnlyEntriesWithThatCorrelationId() {
        Account account = newAccount();
        UUID sharedCorrelationId = UUID.randomUUID();
        ledgerService.record(account, Currency.EUR, EntryType.DEBIT, new BigDecimal("10.00"), new BigDecimal("90.00"), sharedCorrelationId);
        ledgerService.record(account, Currency.USD, EntryType.CREDIT, new BigDecimal("11.20"), new BigDecimal("11.20"), sharedCorrelationId);
        ledgerService.record(account, Currency.EUR, EntryType.CREDIT, new BigDecimal("1.00"), new BigDecimal("91.00"), UUID.randomUUID());

        assertThat(ledgerService.findEntries(sharedCorrelationId)).hasSize(2);
    }

    @Test
    @Transactional
    void findEntryReturnsTheEntryOfTheRequestedType() {
        Account account = newAccount();
        UUID correlationId = UUID.randomUUID();
        ledgerService.record(account, Currency.EUR, EntryType.CREDIT, new BigDecimal("10.00"), new BigDecimal("10.00"), correlationId);

        assertThat(ledgerService.findEntry(correlationId, EntryType.CREDIT).getAmount()).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> ledgerService.findEntry(correlationId, EntryType.DEBIT))
                .isInstanceOf(IllegalStateException.class);
    }
}
