package ee.yvonne.bank.account;

import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
import java.util.List;

@WebMvcTest(AccountController.class)
class AccountControllerTests {
    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;

    @Test 
    void getBalancesReturnsAccountNumberAndBalances() throws Exception {
        when(accountService.getBalances("TEST-1")).thenReturn(new AccountBalancesResult("TEST-1", 
            List.of(
                new CurrencyBalance(Currency.EUR, new BigDecimal("10.00")),
                new CurrencyBalance(Currency.USD, new BigDecimal("20.00"))
            )));

        mockMvc.perform(get("/accounts/TEST-1/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("TEST-1"))
            .andExpect(jsonPath("$.balances[0].amount").value(10.00))
            .andExpect(jsonPath("$.balances[0].currency").value("EUR"))
            .andExpect(jsonPath("$.balances[1].amount").value(20.00))
            .andExpect(jsonPath("$.balances[1].currency").value("USD"));
    }

    @Test
    void getBalancesForUnknownAccountReturns404() throws Exception {
        when(accountService.getBalances("TEST-1")).thenThrow(new AccountNotFoundException("TEST-1"));

        mockMvc.perform(get("/accounts/TEST-1/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBalancesForAccountWithNoBalancesReturnsEmptyList() throws Exception {
        when(accountService.getBalances("TEST-1")).thenReturn(new AccountBalancesResult("TEST-1", List.of()));

        mockMvc.perform(get("/accounts/TEST-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances").isEmpty());
    }
}
