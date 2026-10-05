package ee.yvonne.bank.ledger;

import org.springframework.data.repository.Repository;

public interface TransactionRepository extends Repository<AccountTransaction, Long> {
    AccountTransaction save(AccountTransaction transaction);
}
