package ee.yvonne.bank.account;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import ee.yvonne.bank.common.Currency;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value ="3000")})
    Optional<AccountBalance> findForUpdateByAccountIdAndCurrency(Long accountId, Currency currency);
    
    Optional<AccountBalance> findByAccountIdAndCurrency(Long accountId, Currency currency);
    
    List<AccountBalance> findByAccountId(Long accountId);
}
