package ee.yvonne.bank.account;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ee.yvonne.bank.account.dto.AccountBalancesResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
}
