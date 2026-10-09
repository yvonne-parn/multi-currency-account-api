package ee.yvonne.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

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
import ee.yvonne.bank.account.dto.ExchangeResult;
import ee.yvonne.bank.common.AccountNotFoundException;
import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.ExternalSystemException;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.common.InvalidCurrencyExchangeException;

@WebMvcTest(AccountController.class)
class AccountControllerTests {
    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;

    private ResultActions call(String endpoint, String json) throws Exception {
        return mockMvc.perform(post("/accounts/TEST-1/" + endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"10.555", "0.001"})
    void depositWithMoreThanTwoDecimalsReturnsFieldError(String amount) throws Exception {
        call("deposit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50"})
    void depositWithUpToTwoDecimalsIsAccepted(String amount) throws Exception {
        when(accountService.deposit(any(), any(), any())).thenReturn(new AccountBalanceResult("TEST-1", Currency.EUR, new BigDecimal("60.50")));

        call("deposit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}").andExpect(status().isOk())
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
        call("deposit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void depositWithMissingFieldsReportsEachMissingField() throws Exception {
        call("deposit", "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").exists())
            .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void depositWithUnknownCurrencyReturns400() throws Exception {
        call("deposit", "{\"amount\": 10, \"currency\": \"XYZ\"}").andExpect(status().isBadRequest());
    }

    @Test
    void depositWithMalformedJsonReturns400() throws Exception {
        call("deposit", "{not json").andExpect(status().isBadRequest());
    }

    @Test
    void depositToUnknownAccountReturns404WithMessage() throws Exception {
        when(accountService.deposit(any(), any(), any()))
            .thenThrow(new AccountNotFoundException("TEST-1"));

        call("deposit", "{\"amount\": 10, \"currency\": \"EUR\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: TEST-1"));
    }

    @Test
    void depositWithTooManyIntegerDigitsReturnsFieldError() throws Exception {
        call("deposit", "{\"amount\": 100000000000000000, \"currency\": \"EUR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        when(accountService.deposit(any(), any(), any())).thenThrow(new RuntimeException("secret error message"));

        String body = call("deposit", "{\"amount\": 10, \"currency\": \"EUR\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.555", "0.001"})
    void debitWithMoreThanTwoDecimalsReturnsFieldError(String amount) throws Exception {
        call("debit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50"})
    void debitWithUpToTwoDecimalsIsAccepted(String amount) throws Exception {
        when(accountService.debit(any(), any(), any())).thenReturn(new AccountBalanceResult("TEST-1", Currency.EUR, new BigDecimal("50.50")));

        call("debit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("TEST-1"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.balance").value(50.50));

        verify(accountService).debit(
            eq("TEST-1"),
            eq(Currency.EUR),
            argThat(a -> a.compareTo(new BigDecimal(amount)) == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void debitWithNonPositiveAmountReturnsFieldError(String amount) throws Exception {
        call("debit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void debitWithMissingFieldsReportsEachMissingField() throws Exception {
        call("debit", "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").exists())
            .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void debitToUnknownAccountReturns404WithMessage() throws Exception {
        when(accountService.debit(any(), any(), any()))
            .thenThrow(new AccountNotFoundException("TEST-1"));

        call("debit","{\"amount\": 10, \"currency\": \"EUR\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: TEST-1"));
    }

    @Test
    void debitFromInsufficientFundsReturns422WithMessage() throws Exception {
        when(accountService.debit(any(), any(), any()))
            .thenThrow(new InsufficientFundsException("TEST-1", Currency.EUR));

        call("debit", "{\"amount\": 10, \"currency\": \"EUR\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient EUR balance on account TEST-1"));
    }

    @Test
    void debitWithExternalSystemFailureReturns502WithoutLeakingDetails() throws Exception {
        when(accountService.debit(any(), any(), any()))
            .thenThrow(new ExternalSystemException(
                "Connection refused: https://internal.example.com/log",
                    new RuntimeException("socket timeout")));

        String body = call("debit", "{\"amount\": 10, \"currency\": \"EUR\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("External system unavailable, debit not executed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("internal.example.com", "socket timeout");
    }

    @Test
    void validExchangeReturnsResultAndPassesArgumentsToService() throws Exception {
        ExchangeResult result = new ExchangeResult("TEST-1", Currency.EUR, Currency.USD, new BigDecimal("1.12"), new BigDecimal("10.00"), new BigDecimal("11.20"), new BigDecimal("0.50"), new BigDecimal("22.00"));
        when(accountService.exchange(any(), any(), any(), any())).thenReturn(result);

        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("TEST-1"))
                .andExpect(jsonPath("$.fromCurrency").value("EUR"))
                .andExpect(jsonPath("$.toCurrency").value("USD"))
                .andExpect(jsonPath("$.exchangeRate").value(1.12))
                .andExpect(jsonPath("$.amountDebited").value(10.00))
                .andExpect(jsonPath("$.amountCredited").value(11.20))
                .andExpect(jsonPath("$.fromBalanceAfter").value(0.50))
                .andExpect(jsonPath("$.toBalanceAfter").value(22.00));
    
        verify(accountService).exchange(
            eq("TEST-1"),
            eq(Currency.EUR),
            eq(Currency.USD),
            argThat(a -> a.compareTo(new BigDecimal("10.00")) == 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.555", "0.001"})
    void exchangeWithMoreThanTwoDecimalsReturnsFieldError(String amount) throws Exception {
        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\":" + amount + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(accountService);
    }

    @Test
    void exchangeWithMissingFieldsReportsEachMissingField() throws Exception {
        call("exchange", "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").exists())
            .andExpect(jsonPath("$.errors.fromCurrency").exists())
            .andExpect(jsonPath("$.errors.toCurrency").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void exchangeWithNonPositiveAmountReturnsFieldError(String amount) throws Exception {
        call("exchange", "{\"amount\": " + amount + ", \"fromCurrency\": \"USD\", \"toCurrency\": \"EUR\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void exchangeWithUnknownCurrencyReturns400() throws Exception {
        call("exchange", "{\"amount\": 10, \"fromCurrency\": \"YVH\", \"toCurrency\": \"OOO\"}")
                .andExpect(status().isBadRequest());
    }

    @Test 
    void exchangeWithInvalidCurrencyExchangeExceptionReturns400WithMessage() throws Exception {
        when(accountService.exchange(any(), any(), any(), any())).thenThrow(new InvalidCurrencyExchangeException("Error message"));

        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Error message"));
    }

    @Test
    void exchangeFromUnknownAccountReturns404WithMessage() throws Exception {
        when(accountService.exchange(any(), any(), any(), any())).thenThrow(new AccountNotFoundException("TEST-1"));

        call("exchange","{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: TEST-1"));
    }

    @Test
    void exchangeFromInsufficientFundsReturns422WithMessage() throws Exception {
        when(accountService.exchange(any(), any(), any(), any())).thenThrow(new InsufficientFundsException("TEST-1", Currency.EUR));

        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient EUR balance on account TEST-1"));
    }
}
