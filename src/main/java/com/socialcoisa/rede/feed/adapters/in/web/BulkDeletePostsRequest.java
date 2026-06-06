package com.socialcoisa.rede.feed.adapters.in.web;

import java.util.List;

public record BulkDeletePostsRequest(List<String> postIds, String reason) {
}
