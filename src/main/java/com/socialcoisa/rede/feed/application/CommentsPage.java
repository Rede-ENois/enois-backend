package com.socialcoisa.rede.feed.application;

import java.util.List;

public record CommentsPage(List<CommentView> items, String nextCursor) {
}
