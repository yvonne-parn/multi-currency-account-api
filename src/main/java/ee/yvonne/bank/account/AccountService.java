package ee.yvonne.bank.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.account.dto.ExchangeResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.exchange.ExchangeRateService;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerService;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {
    private final AccountRepository accountRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final LedgerService ledgerService;
    private final ExternalLoggingClient externalLoggingClient;
    private final TransactionTemplate transactionTemplate;
    private final ExchangeRateService exchangeRateService;

    public AccountService(AccountRepository accountRepository, AccountBalanceRepository accountBalanceRepository, LedgerService ledgerService, ExternalLoggingClient externalLoggingClient, TransactionTemplate transactionTemplate, ExchangeRateService exchangeRateService) {
        this.accountRepository = accountRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.ledgerService = ledgerService;
        this.externalLoggingClient = externalLoggingClient;
        this.transactionTemplate = transactionTemplate;
        this.exchangeRateService = exchangeRateService;
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
        Account account = findAccountForUpdate(accountNumber);
        AccountBalance accountBalance = performDeposit(account, currency, amount, UUID.randomUUID());
        return new AccountBalanceResult(accountNumber, currency, accountBalance.getBalance());
    }

    public AccountBalanceResult debit(String accountNumber, Currency currency, BigDecimal amount) {
        if (!accountRepository.existsByAccountNumber(accountNumber)) {
            throw new AccountNotFoundException(accountNumber);
        }
        externalLoggingClient.logDebitAttempt();
        return transactionTemplate.execute(status -> debitInTransaction(accountNumber, currency, amount));
    }

    private AccountBalanceResult debitInTransaction(String accountNumber, Currency currency, BigDecimal amount) {
        Account account = findAccountForUpdate(accountNumber);
        AccountBalance balance = performDebit(account, currency, amount, UUID.randomUUID());
        return new AccountBalanceResult(accountNumber, currency, balance.getBalance());
    }

    @Transactional
    public ExchangeResult exchange(String accountNumber, Currency from, Currency to, BigDecimal amount) {
        BigDecimal debited = toMoney(amount);
        BigDecimal credited = exchangeRateService.convert(from, to, debited);
        BigDecimal rate = exchangeRateService.getExchangeRate(from, to);

        Account account = findAccountForUpdate(accountNumber);

        UUID correlationId = UUID.randomUUID();
        AccountBalance fromBalance = performDebit(account, from, debited, correlationId);
        AccountBalance toBalance = performDeposit(account, to, credited, correlationId);

        return new ExchangeResult(accountNumber, from, to, rate, debited, credited, fromBalance.getBalance(), toBalance.getBalance());
    }

    private AccountBalance performDebit(Account account, Currency currency, BigDecimal amount, UUID correlationId) {
        AccountBalance balance = accountBalanceRepository.findByAccountIdAndCurrency(account.getId(), currency)
                .orElseThrow(() -> new InsufficientFundsException(account.getAccountNumber(), currency));
        balance.debit(amount);
        ledgerService.record(account, currency, EntryType.DEBIT, amount, balance.getBalance(), correlationId);
        return balance;
    }

    private AccountBalance performDeposit(Account account, Currency currency, BigDecimal amount, UUID correlationId) {
        AccountBalance accountBalance = accountBalanceRepository.findByAccountIdAndCurrency(account.getId(), currency)
                    .orElseGet(() -> accountBalanceRepository.save(new AccountBalance(account, currency)));
        accountBalance.credit(amount);
        ledgerService.record(account, currency, EntryType.CREDIT, amount, accountBalance.getBalance(), correlationId);
        return accountBalance;
    }

    private Account findAccountForUpdate(String accountNumber) {
        return accountRepository.findForUpdateByAccountNumber(accountNumber)
            .orElseThrow(() -> new AccountNotFoundException(accountNumber)); 
    }

    private static BigDecimal toMoney(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
