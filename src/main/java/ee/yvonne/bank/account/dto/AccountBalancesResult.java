package ee.yvonne.bank.account.dto;

import java.util.List;

public record AccountBalancesResult(String accountNumber, List<CurrencyBalance> balances) {}
