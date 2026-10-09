package ee.yvonne.bank.exchange;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ee.yvonne.bank.common.Currency;

@ConfigurationProperties(prefix = "exchange")
public record ExchangeProperties(Map<Currency, BigDecimal> rates) {
    public ExchangeProperties {
        Objects.requireNonNull(rates, "Exchange rates are missing");
        for (Currency currency: Currency.values()) {
            BigDecimal rate = rates.get(currency);
            if (rate == null) {
                throw new IllegalArgumentException("Rate for currency " + currency + " is missing");
            }
            if (rate.signum() <= 0) {
                throw new IllegalArgumentException("Rate for currency " + currency + " must be positive");
            }
        }
        rates = Map.copyOf(rates);
    }
}
