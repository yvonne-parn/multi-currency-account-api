package ee.yvonne.bank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan 
public class MultiCurrencyAccountApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(MultiCurrencyAccountApiApplication.class, args);
	}

}
