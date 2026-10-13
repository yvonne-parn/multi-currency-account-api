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
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import ee.yvonne.bank.common.IdempotencyConflictException;
import ee.yvonne.bank.common.InsufficientFundsException;
import ee.yvonne.bank.common.InvalidCurrencyExchangeException;

@WebMvcTest(AccountController.class)
class AccountControllerTests {
    @Autowired MockMvc mockMvc;
    @MockitoBean AccountService accountService;
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private ResultActions call(String endpoint, String json, UUID key) throws Exception {
        return mockMvc.perform(post("/accounts/TEST-1/" + endpoint)
                .header(IDEMPOTENCY_HEADER, key.toString())
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

    static Stream<Arguments> endpointsWithBodies() {
        return Stream.of(
                Arguments.of("deposit", "{\"amount\": 10, \"currency\": \"EUR\"}"),
                Arguments.of("debit", "{\"amount\": 10, \"currency\": \"EUR\"}"),
                Arguments.of("exchange", "{\"amount\": 10, \"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\"}")
            );
    }

    @ParameterizedTest
    @MethodSource("endpointsWithBodies")
    void missingIdempotencyKeyReturns400(String endpoint, String body) throws Exception {
        mockMvc.perform(post("/accounts/TEST-1/" + endpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @MethodSource("endpointsWithBodies")
    void malformedIdempotencyKeyReturns400(String endpoint, String body) throws Exception {
        mockMvc.perform(post("/accounts/TEST-1/" + endpoint)
                        .header("Idempotency-Key", "Hello")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountService);
    }

    @Test
    void idempotencyConflictReturns409WithMessage() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.deposit(any(), any(), any(), any())).thenThrow(new IdempotencyConflictException(key));

        call("deposit", "{\"amount\": 10, \"currency\": \"EUR\"}", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Idempotency key " + key + " was already used for a different request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.555", "0.001"})
    void depositWithMoreThanTwoDecimalsReturnsFieldError(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        call("deposit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}", key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50"})
    void depositWithUpToTwoDecimalsIsAccepted(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.deposit(any(), any(), any(), any())).thenReturn(new AccountBalanceResult("TEST-1", Currency.EUR, new BigDecimal("60.50")));

        call("deposit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}", key).andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("TEST-1"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.balance").value(60.50));

        verify(accountService).deposit(
            eq("TEST-1"),
            eq(Currency.EUR),
            argThat(a -> a.compareTo(new BigDecimal(amount)) == 0),
            eq(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void depositWithNonPositiveAmountReturnsFieldError(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        call("deposit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}", key).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void depositWithMissingFieldsReportsEachMissingField() throws Exception {
        UUID key = UUID.randomUUID();
        call("deposit", "{}", key)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").exists())
            .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void depositWithUnknownCurrencyReturns400() throws Exception {
        UUID key = UUID.randomUUID();
        call("deposit", "{\"amount\": 10, \"currency\": \"XYZ\"}", key).andExpect(status().isBadRequest());
    }

    @Test
    void depositWithMalformedJsonReturns400() throws Exception {
        UUID key = UUID.randomUUID();
        call("deposit", "{not json", key).andExpect(status().isBadRequest());
    }

    @Test
    void depositToUnknownAccountReturns404WithMessage() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.deposit(any(), any(), any(), any()))
            .thenThrow(new AccountNotFoundException("TEST-1"));

        call("deposit", "{\"amount\": 10, \"currency\": \"EUR\"}", key)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: TEST-1"));
    }

    @Test
    void depositWithTooManyIntegerDigitsReturnsFieldError() throws Exception {
        UUID key = UUID.randomUUID();
        call("deposit", "{\"amount\": 100000000000000000, \"currency\": \"EUR\"}", key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.deposit(any(), any(), any(), any())).thenThrow(new RuntimeException("secret error message"));

        String body = call("deposit", "{\"amount\": 10, \"currency\": \"EUR\"}", key)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.555", "0.001"})
    void debitWithMoreThanTwoDecimalsReturnsFieldError(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        call("debit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}", key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(accountService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.5", "10.50"})
    void debitWithUpToTwoDecimalsIsAccepted(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.debit(any(), any(), any(), any())).thenReturn(new AccountBalanceResult("TEST-1", Currency.EUR, new BigDecimal("50.50")));

        call("debit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}", key).andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber").value("TEST-1"))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.balance").value(50.50));

        verify(accountService).debit(
            eq("TEST-1"),
            eq(Currency.EUR),
            argThat(a -> a.compareTo(new BigDecimal(amount)) == 0),
            eq(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void debitWithNonPositiveAmountReturnsFieldError(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        call("debit", "{\"amount\": " + amount + ", \"currency\": \"EUR\"}", key).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void debitWithMissingFieldsReportsEachMissingField() throws Exception {
        UUID key = UUID.randomUUID();
        call("debit", "{}", key)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").exists())
            .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void debitToUnknownAccountReturns404WithMessage() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.debit(any(), any(), any(), any()))
            .thenThrow(new AccountNotFoundException("TEST-1"));

        call("debit","{\"amount\": 10, \"currency\": \"EUR\"}", key)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: TEST-1"));
    }

    @Test
    void debitFromInsufficientFundsReturns422WithMessage() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.debit(any(), any(), any(), any()))
            .thenThrow(new InsufficientFundsException("TEST-1", Currency.EUR));

        call("debit", "{\"amount\": 10, \"currency\": \"EUR\"}", key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient EUR balance on account TEST-1"));
    }

    @Test
    void debitWithExternalSystemFailureReturns502WithoutLeakingDetails() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.debit(any(), any(), any(), any()))
            .thenThrow(new ExternalSystemException(
                "Connection refused: https://internal.example.com/log",
                    new RuntimeException("socket timeout")));

        String body = call("debit", "{\"amount\": 10, \"currency\": \"EUR\"}", key)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("External system unavailable, debit not executed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("internal.example.com", "socket timeout");
    }

    @Test
    void validExchangeReturnsResultAndPassesArgumentsToService() throws Exception {
        UUID key = UUID.randomUUID();
        ExchangeResult result = new ExchangeResult("TEST-1", Currency.EUR, Currency.USD, new BigDecimal("1.12"), new BigDecimal("10.00"), new BigDecimal("11.20"), new BigDecimal("0.50"), new BigDecimal("22.00"));
        when(accountService.exchange(any(), any(), any(), any(), any())).thenReturn(result);

        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}", key)
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
            argThat(a -> a.compareTo(new BigDecimal("10.00")) == 0),
            eq(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.555", "0.001"})
    void exchangeWithMoreThanTwoDecimalsReturnsFieldError(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\":" + amount + "}", key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());

        verifyNoInteractions(accountService);
    }

    @Test
    void exchangeWithMissingFieldsReportsEachMissingField() throws Exception {
        UUID key = UUID.randomUUID();
        call("exchange", "{}", key)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.amount").exists())
            .andExpect(jsonPath("$.errors.fromCurrency").exists())
            .andExpect(jsonPath("$.errors.toCurrency").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    void exchangeWithNonPositiveAmountReturnsFieldError(String amount) throws Exception {
        UUID key = UUID.randomUUID();
        call("exchange", "{\"amount\": " + amount + ", \"fromCurrency\": \"USD\", \"toCurrency\": \"EUR\"}", key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void exchangeWithUnknownCurrencyReturns400() throws Exception {
        UUID key = UUID.randomUUID();
        call("exchange", "{\"amount\": 10, \"fromCurrency\": \"YVH\", \"toCurrency\": \"OOO\"}", key)
                .andExpect(status().isBadRequest());
    }

    @Test 
    void exchangeWithInvalidCurrencyExchangeExceptionReturns400WithMessage() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.exchange(any(), any(), any(), any(), any())).thenThrow(new InvalidCurrencyExchangeException("Error message"));

        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}", key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Error message"));
    }

    @Test
    void exchangeFromUnknownAccountReturns404WithMessage() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.exchange(any(), any(), any(), any(), any())).thenThrow(new AccountNotFoundException("TEST-1"));

        call("exchange","{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}", key)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found: TEST-1"));
    }

    @Test
    void exchangeFromInsufficientFundsReturns422WithMessage() throws Exception {
        UUID key = UUID.randomUUID();
        when(accountService.exchange(any(), any(), any(), any(), any())).thenThrow(new InsufficientFundsException("TEST-1", Currency.EUR));

        call("exchange", "{\"fromCurrency\": \"EUR\", \"toCurrency\": \"USD\", \"amount\": 10}", key)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Insufficient EUR balance on account TEST-1"));
    }
}
