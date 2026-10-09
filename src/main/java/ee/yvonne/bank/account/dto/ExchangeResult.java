package ee.yvonne.bank.account.dto;

import java.math.BigDecimal;

import ee.yvonne.bank.common.Currency;

public record ExchangeResult(
    String accountNumber,
    Currency fromCurrency,
    Currency toCurrency,
    BigDecimal exchangeRate,
    BigDecimal amountDebited,
    BigDecimal amountCredited,
    BigDecimal fromBalanceAfter,
    BigDecimal toBalanceAfter) {}