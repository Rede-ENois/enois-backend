package com.socialcoisa.rede.feed.application;

import java.util.List;

public record BulkDeleteResult(String status, List<BulkDeleteItemResult> items) {
}
