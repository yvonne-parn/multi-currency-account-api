package ee.yvonne.bank.common;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountNumber, Currency currency) {
        super("Insufficient " + currency + " balance on account " + accountNumber);
    }
}