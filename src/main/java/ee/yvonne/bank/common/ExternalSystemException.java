package ee.yvonne.bank.common;

public class ExternalSystemException extends RuntimeException {
    public ExternalSystemException(String message) {
        super(message);
    }

    public ExternalSystemException(String message, Throwable e) {
        super(message, e);
    }
}
