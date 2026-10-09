package ee.yvonne.bank.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import ee.yvonne.bank.common.Currency;

@SpringBootTest 
class ExchangePropertiesIntegrationTests {
    @Autowired ExchangeProperties properties;

    @Test
    void realConfigurationFileBindsRatesForAllCurrencies() {
        assertThat(properties.rates()).containsOnlyKeys(Currency.values());
        assertThat(properties.rates().values()).allSatisfy(rate -> assertThat(rate).isPositive());
        assertThat(properties.rates().get(Currency.EUR)).isEqualByComparingTo("1");
    }
}
