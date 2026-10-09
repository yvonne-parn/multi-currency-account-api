package ee.yvonne.bank.account.dto;

import java.math.BigDecimal;

import ee.yvonne.bank.common.Currency;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ExchangeRequest(@NotNull Currency fromCurrency, @NotNull Currency toCurrency, @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {}
