package ee.yvonne.bank.idempotency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import ee.yvonne.bank.account.Account;
import ee.yvonne.bank.common.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idempotency_record",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "idempotency_key"}),
        indexes = @Index(name = "idx_idempotency_created", columnList = "created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Operation operation;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;
    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 3)
    private Currency toCurrency;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, updatable = false)
    private UUID correlationId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public IdempotencyRecord(Account account, UUID idempotencyKey, IdempotencyRequestContent requestContent, UUID correlationId) {
        this.account = account;
        this.idempotencyKey = idempotencyKey;
        this.operation = requestContent.operation();
        this.currency = requestContent.currency();
        this.toCurrency = requestContent.toCurrency();
        this.amount = requestContent.amount();
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }

    public boolean hasSameContent(IdempotencyRequestContent requestContent) {
        return this.amount.compareTo(requestContent.amount()) == 0 
                && this.currency == requestContent.currency() 
                && this.toCurrency == requestContent.toCurrency() 
                && this.operation == requestContent.operation();
    }
}