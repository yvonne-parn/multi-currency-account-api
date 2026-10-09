package ee.yvonne.bank.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ee.yvonne.bank.common.Currency;

class ExchangePropertiesTests {

    private Map<Currency, BigDecimal> validRates() {
        return Map.of(Currency.EUR, new BigDecimal("1.0"),
                Currency.USD, new BigDecimal("1.12"),
                Currency.SEK, new BigDecimal("11.25"),
                Currency.GBP, new BigDecimal("0.85"));
    }

    @Test
    void validRatesAreAccepted() {
        ExchangeProperties properties = new ExchangeProperties(validRates());

        assertThat(properties.rates()).containsOnlyKeys(Currency.values());
        assertThat(properties.rates().get(Currency.USD)).isEqualByComparingTo("1.12");
    }

    @Test
    void missingCurrencyIsRejected() {
        Map<Currency, BigDecimal> rates = Map.of(Currency.EUR, new BigDecimal("1.0"), Currency.USD, new BigDecimal("1.12"), Currency.GBP, new BigDecimal("0.85"));

        assertThatThrownBy(() -> new ExchangeProperties(rates))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Rate for currency SEK is missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.12"})
    void zeroAndNegativeRateThrows(String rate) {
        Map<Currency, BigDecimal> rates = Map.of(
            Currency.EUR, new BigDecimal(rate), 
            Currency.USD, new BigDecimal("1.12"), 
            Currency.GBP, new BigDecimal("0.85"), 
            Currency.SEK, new BigDecimal("11.25"));

        assertThatThrownBy(() -> new ExchangeProperties(rates))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Rate for currency EUR must be positive");
    }

    @Test 
    void nullRatesMapIsRejected() {
        assertThatThrownBy(() -> new ExchangeProperties(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("Exchange rates are missing");
    }

    @Test
    void emptyRatesMapIsRejected() {
        assertThatThrownBy(() -> new ExchangeProperties(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void ratesAreImmutableAfterConstruction() {
        ExchangeProperties properties = new ExchangeProperties(validRates());

        assertThatThrownBy(() -> properties.rates().put(Currency.USD, BigDecimal.TEN))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
