package com.socialcoisa.rede.feed.adapters.in.web;

import java.util.List;

public record CommentOnPostRequest(String parentCommentId, String content, List<String> mentionedUserIds) {
}
