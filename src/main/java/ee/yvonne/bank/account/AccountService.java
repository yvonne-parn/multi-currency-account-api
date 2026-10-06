package ee.yvonne.bank.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerService;

import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final LedgerService ledgerService;

    public AccountService(AccountRepository accountRepository, AccountBalanceRepository accountBalanceRepository, LedgerService ledgerService) {
        this.accountRepository = accountRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.ledgerService = ledgerService;
    }

    public AccountBalancesResult getBalances(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        List<CurrencyBalance> balances = accountBalanceRepository.findByAccountId(account.getId()).stream()
                .map(ab -> new CurrencyBalance(ab.getCurrency(), ab.getBalance()))
                .toList();
        return new AccountBalancesResult(accountNumber, balances);
    }

    @Transactional
    public AccountBalanceResult deposit(String accountNumber, Currency currency, BigDecimal amount) {
        Account account = accountRepository.findForUpdateByAccountNumber(accountNumber)
                    .orElseThrow(() -> new AccountNotFoundException(accountNumber)); 
        AccountBalance accountBalance = accountBalanceRepository.findByAccountIdAndCurrency(account.getId(), currency)
                    .orElseGet(() -> accountBalanceRepository.save(new AccountBalance(account, currency)));
        accountBalance.credit(amount);
        ledgerService.record(account, currency, EntryType.CREDIT, amount, accountBalance.getBalance(), UUID.randomUUID());
        return new AccountBalanceResult(accountNumber, currency, accountBalance.getBalance());
    }
}
