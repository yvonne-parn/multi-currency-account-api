package ee.yvonne.bank.account;

import java.util.List;

import org.springframework.stereotype.Service;

import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.common.AccountNotFoundException;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final AccountBalanceRepository accountBalanceRepository;

    public AccountService(AccountRepository accountRepository, AccountBalanceRepository accountBalanceRepository) {
        this.accountRepository = accountRepository;
        this.accountBalanceRepository = accountBalanceRepository;
    }

    public AccountBalancesResult getBalances(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        List<CurrencyBalance> balances = accountBalanceRepository.findByAccountId(account.getId()).stream()
                .map(ab -> new CurrencyBalance(ab.getCurrency(), ab.getBalance()))
                .toList();
        return new AccountBalancesResult(accountNumber, balances);
    }
}
