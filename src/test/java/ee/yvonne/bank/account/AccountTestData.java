package ee.yvonne.bank.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.ledger.LedgerEntry;
import ee.yvonne.bank.ledger.LedgerEntryRepository;

class AccountTestData {
    private final AccountRepository accountRepository;
    private final AccountBalanceRepository balanceRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final AccountService accountService;

    AccountTestData(AccountRepository accountRepository, AccountBalanceRepository balanceRepository, LedgerEntryRepository ledgerRepository, AccountService accountService) {
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
        this.accountService = accountService;
    }

    Account newAccount() {
        return accountRepository.save(new Account("TEST-" + UUID.randomUUID(), "Test Holder"));
    }

    Account accountWith(Currency currency, String amount) {
        Account account = newAccount();
        accountService.deposit(account.getAccountNumber(), currency, new BigDecimal(amount), UUID.randomUUID());
        return account;
    }

    Optional<AccountBalance> balanceOf(Account account, Currency currency) {
        return balanceRepository.findByAccountIdAndCurrency(account.getId(), currency);
    }

    List<LedgerEntry> ledgerOf(Account account) {
        return ledgerRepository.findByAccountIdOrderByIdAsc(account.getId());
    }
}