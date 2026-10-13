package ee.yvonne.bank.common;

import java.util.UUID;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(UUID key) {
        super("Idempotency key " + key + " was already used for a different request");
    }
}
