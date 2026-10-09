package ee.yvonne.bank.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.InvalidCurrencyExchangeException;

class ExchangeRateServiceTests {
    ExchangeRateService service;

    @BeforeEach 
    void setUp() {
        Map<Currency, BigDecimal> rates = Map.of(Currency.EUR, new BigDecimal("1.00"), Currency.GBP, new BigDecimal("0.85"), Currency.USD, new BigDecimal("1.12"), Currency.SEK, new BigDecimal("11.25"));
        ExchangeProperties properties = new ExchangeProperties(rates);
        service = new ExchangeRateService(properties);
    }

    @ParameterizedTest
    @CsvSource({
        "EUR, USD, 100.00, 112.00",
        "USD, EUR, 112.00, 100.00",
        "EUR, SEK, 100.00, 1125.00",
        "SEK, EUR, 112.50, 10.00",
        "EUR, GBP, 100.00, 85.00",
        "GBP, EUR, 85.00, 100.00"
    })
    void convertsBetweenSupportedCurrencies(Currency from, Currency to, String amountToConvert, String expected) {
        BigDecimal result = service.convert(from, to, new BigDecimal(amountToConvert));
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void roundsResultToTwoDecimalPlacesUsingHalfEven() {
        BigDecimal result = service.convert(Currency.EUR, Currency.USD, new BigDecimal("1.005"));

        assertThat(result).isEqualTo("1.13");
    }

    @Test
    void rejectsExchangeBetweenSameCurrency() {
        assertThatThrownBy(() -> service.convert(Currency.EUR, Currency.EUR, new BigDecimal("10.00")))
                .isInstanceOf(InvalidCurrencyExchangeException.class)
                .hasMessage("Cannot exchange EUR to EUR");
    }

    @ParameterizedTest 
    @ValueSource(strings = {"0.00", "-5.00"}) 
    void rejectsNonPositiveAmountExchanges(String amount) {
        assertThatThrownBy(() -> service.convert(Currency.EUR, Currency.USD, new BigDecimal(amount)))
                .isInstanceOf(InvalidCurrencyExchangeException.class)
                .hasMessage("Amount must be positive");
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> service.convert(Currency.EUR, Currency.USD, null))
            .isInstanceOf(InvalidCurrencyExchangeException.class)
            .hasMessage("Amount must be positive");
    }

    @Test 
    void rejectsAmountsThatConvertToZero() {
        assertThatThrownBy(() -> service.convert(Currency.SEK, Currency.EUR, new BigDecimal("0.01")))
                .isInstanceOf(InvalidCurrencyExchangeException.class)
                .hasMessage("Amount too small to exchange");
    }

    @Test 
    void getExchangeRateRejectsSameCurrencyExchanges() {
        assertThatThrownBy(() -> service.getExchangeRate(Currency.EUR, Currency.EUR))
            .isInstanceOf(InvalidCurrencyExchangeException.class)
            .hasMessage("Cannot exchange EUR to EUR");
    }

    @ParameterizedTest
    @CsvSource({"EUR, USD, 1.120000", 
                "USD, EUR, 0.892857", 
                "EUR, SEK, 11.250000",
                "SEK, EUR, 0.088889",
                "EUR, GBP, 0.850000",
                "GBP, EUR, 1.176471",
                "USD, SEK, 10.044643",
                "SEK, USD, 0.099556",
                "USD, GBP, 0.758929",
                "GBP, USD, 1.317647",
                "SEK, GBP, 0.075556",
                "GBP, SEK, 13.235294"
            }) 
    void givesCorrectExchangeRatesWithCorrectScale(Currency fromCurrency, Currency toCurrency, String expected) {
        BigDecimal result = service.getExchangeRate(fromCurrency, toCurrency);

        assertThat(result.scale()).isEqualTo(6);
        assertThat(result).isEqualTo(expected);
    }
}
