package ee.yvonne.bank.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ee.yvonne.bank.common.Currency;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {    
    Optional<AccountBalance> findByAccountIdAndCurrency(Long accountId, Currency currency);
    
    List<AccountBalance> findByAccountId(Long accountId);
}
