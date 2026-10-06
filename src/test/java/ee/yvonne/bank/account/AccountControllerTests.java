package ee.yvonne.bank.account;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import ee.yvonne.bank.account.dto.AccountBalanceResult;
import ee.yvonne.bank.account.dto.AccountBalancesResult;
import ee.yvonne.bank.account.dto.CurrencyBalance;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

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

    private ResultActions deposit(String json) throws Exception {
        return mockMvc.perform(post("/accounts/TEST-1/deposit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.555", "0.001"})
    void depositWithMoreThanTwoDecimalsReturnsFieldError(String amount) throws Exception {
        deposit("{\"amount\": " + amount + ", \"currency\": \"EUR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50"})
    void depositWithUpToTwoDecimalsIsAccepted(String amount) throws Exception {
        when(accountService.deposit(any(), any(), any())).thenReturn(new AccountBalanceResult("TEST-1", Currency.EUR, new BigDecimal("60.50")));

        deposit("{\"amount\": " + amount + ", \"currency\": \"EUR\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("TEST-1"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.balance").value(60.50));

        verify(accountService).deposit(
            eq("TEST-1"),
            eq(Currency.EUR),
            argThat(a -> a.compareTo(new BigDecimal(amount)) == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void depositWithNonPositiveAmountReturnsFieldError(String amount) throws Exception {
        deposit("{\"amount\": " + amount + ", \"currency\": \"EUR\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void depositWithMissingFieldsReportsEachMissingField() throws Exception {
        deposit("{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").exists())
            .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void depositWithUnknownCurrencyReturns400() throws Exception {
        deposit("{\"amount\": 10, \"currency\": \"XYZ\"}").andExpect(status().isBadRequest());
    }

    @Test
    void depositWithMalformedJsonReturns400() throws Exception {
        deposit("{not json").andExpect(status().isBadRequest());
    }

    @Test
    void depositToUnknownAccountReturns404WithMessage() throws Exception {
        when(accountService.deposit(any(), any(), any()))
            .thenThrow(new AccountNotFoundException("TEST-1"));

        deposit("{\"amount\": 10, \"currency\": \"EUR\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: TEST-1"));
    }

    @Test
    void depositWithTooManyIntegerDigitsReturnsFieldError() throws Exception {
        deposit("{\"amount\": 100000000000000000, \"currency\": \"EUR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }
}
