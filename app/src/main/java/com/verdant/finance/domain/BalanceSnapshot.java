package com.verdant.finance.domain;

import com.verdant.common.domain.Money;

import java.util.UUID;

public record BalanceSnapshot(UUID accountId, long asOfEntryId, Money runningBalance) { }
