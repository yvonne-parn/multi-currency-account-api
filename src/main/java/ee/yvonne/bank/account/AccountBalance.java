package ee.yvonne.bank.account;

import java.math.BigDecimal;
import java.time.Instant;

import ee.yvonne.bank.common.Currency;
import ee.yvonne.bank.common.InsufficientFundsException;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "account_balance", uniqueConstraints = {@UniqueConstraint(columnNames = {"account_id", "currency"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountBalance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;
    @Column(nullable = false, precision = 19, scale = 2, check = @CheckConstraint(name = "ck_balance_non_negative", constraint = "balance >= 0"))
    private BigDecimal balance;
    @Column(nullable = false)
    private Instant updatedAt;

    public AccountBalance(Account account, Currency currency) {
        this.account = account;
        this.currency = currency;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.updatedAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        requirePositive(amount);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(account.getAccountNumber(), currency);
        }
        balance = balance.subtract(amount);
        updatedAt = Instant.now();
    }

    public void credit(BigDecimal amount) {
        requirePositive(amount);
        balance = balance.add(amount);
        updatedAt = Instant.now();
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }
}
