package ee.yvonne.bank.account.dto;

import java.math.BigDecimal;

import ee.yvonne.bank.common.Currency;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DepositRequest(@NotNull Currency currency, @NotNull @Positive @Digits(integer = 17, fraction = 2, message = "Amount must have at most 2 decimal places") BigDecimal amount) {}
