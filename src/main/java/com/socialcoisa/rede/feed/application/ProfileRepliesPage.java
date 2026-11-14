package com.socialcoisa.rede.feed.application;

import java.util.List;

public record ProfileRepliesPage(List<ProfileReplyView> items, String nextCursor) {
}
