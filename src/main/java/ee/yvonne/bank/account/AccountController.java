package ee.yvonne.bank.account;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.DepositRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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
    public AccountBalanceResult addMoney(@PathVariable String accountNumber, @Valid @RequestBody DepositRequest request) { 
        return accountService.deposit(accountNumber, request.currency(), request.amount());
    }
}
