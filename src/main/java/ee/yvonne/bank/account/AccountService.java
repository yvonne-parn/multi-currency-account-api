package ee.yvonne.bank.account;

import static ee.yvonne.bank.ledger.LedgerService.entryOfType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.account.dto.ExchangeResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.exchange.ExchangeRateService;
import ee.yvonne.bank.external.ExternalLoggingClient;
import ee.yvonne.bank.idempotency.IdempotencyRecord;
import ee.yvonne.bank.idempotency.IdempotencyRequestContent;
import ee.yvonne.bank.idempotency.IdempotencyService;
import ee.yvonne.bank.ledger.EntryType;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final LedgerService ledgerService;
    private final ExternalLoggingClient externalLoggingClient;
    private final TransactionTemplate transactionTemplate;
    private final ExchangeRateService exchangeRateService;
    private final IdempotencyService idempotencyService;

    public AccountBalancesResult getBalances(String accountNumber) {
        Account account = findAccount(accountNumber);
        List<CurrencyBalance> balances = accountBalanceRepository.findByAccountId(account.getId()).stream()
                .map(ab -> new CurrencyBalance(ab.getCurrency(), ab.getBalance()))
                .toList();
        return new AccountBalancesResult(accountNumber, balances);
    }

    @Transactional
    public AccountBalanceResult deposit(String accountNumber, Currency currency, BigDecimal amount, UUID idempotencyKey) {
        BigDecimal normalizedAmount = toMoney(amount);
        Account account = findAccountForUpdate(accountNumber);

        IdempotencyRequestContent content = IdempotencyRequestContent.deposit(normalizedAmount, currency);

        Optional<IdempotencyRecord> duplicate = idempotencyService.findDuplicate(account.getId(), idempotencyKey, content);

        if (duplicate.isPresent()) {
            return getDuplicateBalanceResult(accountNumber, duplicate.get().getCorrelationId(), EntryType.CREDIT);
        }

        UUID correlationId = recordRequest(account, idempotencyKey, content);
        AccountBalance accountBalance = performDeposit(account, currency, normalizedAmount, correlationId);
        return new AccountBalanceResult(accountNumber, currency, accountBalance.getBalance());
    }

    public AccountBalanceResult debit(String accountNumber, Currency currency, BigDecimal amount, UUID idempotencyKey) {
        BigDecimal normalizedAmount = toMoney(amount);
        IdempotencyRequestContent content = IdempotencyRequestContent.debit(normalizedAmount, currency);

        Account account = findAccount(accountNumber);
        Optional<IdempotencyRecord> duplicate = idempotencyService.findDuplicate(account.getId(), idempotencyKey, content);

        if (duplicate.isPresent()) {
            return getDuplicateBalanceResult(accountNumber, duplicate.get().getCorrelationId(), EntryType.DEBIT);
        }

        externalLoggingClient.logDebitAttempt();
        return transactionTemplate.execute(status -> debitInTransaction(accountNumber, content, idempotencyKey));
    }

    private AccountBalanceResult debitInTransaction(String accountNumber, IdempotencyRequestContent content, UUID idempotencyKey) {
        Account account = findAccountForUpdate(accountNumber);
        
        Optional<IdempotencyRecord> duplicate = idempotencyService.findDuplicate(account.getId(), idempotencyKey, content);

        if (duplicate.isPresent()) {
            return getDuplicateBalanceResult(accountNumber, duplicate.get().getCorrelationId(), EntryType.DEBIT);
        }

        UUID correlationId = recordRequest(account, idempotencyKey, content);
        AccountBalance balance = performDebit(account, content.currency(), content.amount(), correlationId);
        return new AccountBalanceResult(accountNumber, content.currency(), balance.getBalance());
    }

    @Transactional
    public ExchangeResult exchange(String accountNumber, Currency from, Currency to, BigDecimal amount, UUID idempotencyKey) {
        BigDecimal debited = toMoney(amount);
        BigDecimal credited = exchangeRateService.convert(from, to, debited);
        BigDecimal rate = exchangeRateService.getExchangeRate(from, to);

        Account account = findAccountForUpdate(accountNumber);

        IdempotencyRequestContent content = IdempotencyRequestContent.exchange(debited, from, to);

        Optional<IdempotencyRecord> duplicate = idempotencyService.findDuplicate(account.getId(), idempotencyKey, content);

        if (duplicate.isPresent()) {
            return getDuplicateExchangeResult(accountNumber, from, to, rate, duplicate.get().getCorrelationId());
        }

        UUID correlationId = recordRequest(account, idempotencyKey, content);

        AccountBalance fromBalance = performDebit(account, from, debited, correlationId);
        AccountBalance toBalance = performDeposit(account, to, credited, correlationId);

        return new ExchangeResult(accountNumber, from, to, rate, debited, credited, fromBalance.getBalance(), toBalance.getBalance());
    }

    private UUID recordRequest(Account account, UUID idempotencyKey, IdempotencyRequestContent content) {
        UUID correlationId = UUID.randomUUID();
        idempotencyService.save(account, idempotencyKey, content, correlationId);
        return correlationId;
    }

    private AccountBalanceResult getDuplicateBalanceResult(String accountNumber, UUID correlationId, EntryType type) {
        LedgerEntry entry = ledgerService.findEntry(correlationId, type);
        return new AccountBalanceResult(accountNumber, entry.getCurrency(), entry.getNewBalance());
    }

    private ExchangeResult getDuplicateExchangeResult(String accountNumber, Currency from, Currency to, BigDecimal rate, UUID correlationId) {
        List<LedgerEntry> entries = ledgerService.findEntries(correlationId);
        LedgerEntry debit = entryOfType(entries, EntryType.DEBIT, correlationId);
        LedgerEntry credit = entryOfType(entries, EntryType.CREDIT, correlationId);
        return new ExchangeResult(accountNumber, from, to, rate, debit.getAmount(), credit.getAmount(), debit.getNewBalance(), credit.getNewBalance());
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

    private Account findAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
            .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    private Account findAccountForUpdate(String accountNumber) {
        return accountRepository.findForUpdateByAccountNumber(accountNumber)
            .orElseThrow(() -> new AccountNotFoundException(accountNumber)); 
    }

    private static BigDecimal toMoney(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
