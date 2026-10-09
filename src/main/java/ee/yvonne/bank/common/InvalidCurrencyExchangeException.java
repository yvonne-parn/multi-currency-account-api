package ee.yvonne.bank.common;

public class InvalidCurrencyExchangeException extends RuntimeException {
    public InvalidCurrencyExchangeException(String message) {
        super(message);
    }
}
