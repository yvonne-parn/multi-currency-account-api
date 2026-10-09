package ee.yvonne.bank.account;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.DebitRequest;
import ee.yvonne.bank.account.dto.DepositRequest;
import ee.yvonne.bank.account.dto.ExchangeRequest;
import ee.yvonne.bank.account.dto.ExchangeResult;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
public class AccountController {
    private final AccountService accountService;

    AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/{accountNumber}/balance")
    public AccountBalancesResult getBalances(@PathVariable String accountNumber) {
        return accountService.getBalances(accountNumber); 
    }

    @PostMapping("/{accountNumber}/deposit")
    public AccountBalanceResult deposit(@PathVariable String accountNumber, @Valid @RequestBody DepositRequest request) { 
        return accountService.deposit(accountNumber, request.currency(), request.amount());
    }

    @PostMapping("/{accountNumber}/debit")
    public AccountBalanceResult debit(@PathVariable String accountNumber, @Valid @RequestBody DebitRequest request) {
        return accountService.debit(accountNumber, request.currency(), request.amount());
    }

    @PostMapping("/{accountNumber}/exchange")
    public ExchangeResult exchange(@PathVariable String accountNumber, @Valid @RequestBody ExchangeRequest request) {
        return accountService.exchange(accountNumber, request.fromCurrency(), request.toCurrency(), request.amount());
    }
} 
