package com.socialcoisa.rede.feed.application;

import java.util.List;

public record FeedPage(List<PostView> items, String nextCursor) {
}
