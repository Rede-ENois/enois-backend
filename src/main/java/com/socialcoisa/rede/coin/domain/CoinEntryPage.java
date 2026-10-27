package com.socialcoisa.rede.coin.domain;

import java.util.List;

public record CoinEntryPage(List<CoinEntry> entries, boolean hasNext) {
}
