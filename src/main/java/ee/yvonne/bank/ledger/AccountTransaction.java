package ee.yvonne.bank.ledger;

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
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "account_transaction", indexes = {
        @Index(name = "idx_tx_account_created", columnList = "account_id, created_at"),
        @Index(name = "idx_tx_correlation", columnList = "correlation_id")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class AccountTransaction {
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private Currency currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;
    @Enumerated(EnumType.STRING) 
    @Column(nullable = false, updatable = false)
    private TransactionReason reason;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal newBalance;
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AccountTransaction(Account account, Currency currency, TransactionType type,
        TransactionReason reason, BigDecimal amount, BigDecimal newBalance,
        UUID correlationId) {
        this.account = account;
        this.currency = currency;
        this.type = type;
        this.reason = reason;
        this.amount = amount;
        this.newBalance = newBalance;
        this.correlationId = correlationId;
        this.createdAt = Instant.now();
    }
}
