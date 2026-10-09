package ee.yvonne.bank.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.InvalidCurrencyExchangeException;

@Service 
public class ExchangeRateService {
    private final ExchangeProperties properties;

    public ExchangeRateService(ExchangeProperties properties) {
        this.properties = properties;
    }

    private BigDecimal getRate(Currency currency) {
        return properties.rates().get(currency);
    }

    public BigDecimal convert(Currency from, Currency to, BigDecimal amountToExchange) {
        requireDifferentCurrencies(from, to);

        if (amountToExchange == null || amountToExchange.signum() <= 0) {
            throw new InvalidCurrencyExchangeException("Amount must be positive");
        }

        BigDecimal rateTo = getRate(to);
        BigDecimal rateFrom = getRate(from);
        BigDecimal converted = amountToExchange.multiply(rateTo).divide(rateFrom, 2, RoundingMode.HALF_EVEN);

        if (converted.signum() <= 0) {
            throw new InvalidCurrencyExchangeException("Amount too small to exchange");
        }   

        return converted;
    }

    public BigDecimal getExchangeRate(Currency from, Currency to) {
        requireDifferentCurrencies(from, to);
        return getRate(to).divide(getRate(from), 6, RoundingMode.HALF_EVEN);
    }

    private void requireDifferentCurrencies(Currency from, Currency to) {
        if (from.equals(to)) {
            throw new InvalidCurrencyExchangeException("Cannot exchange " + from + " to " + to);
        }
    }
}
