package ee.yvonne.bank.account.dto;

import java.math.BigDecimal;

import ee.yvonne.bank.common.Currency;

public record AccountBalanceResult(String accountNumber, Currency currency, BigDecimal balance) {}