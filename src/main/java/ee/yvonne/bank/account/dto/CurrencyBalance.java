package ee.yvonne.bank.account.dto;

import java.math.BigDecimal;

import ee.yvonne.bank.common.Currency;

public record CurrencyBalance(Currency currency, BigDecimal amount) {}
