package com.socialcoisa.rede.feed.application;

/** State of a question after accepting or unaccepting its answer. */
public record AcceptedAnswerView(String postId, String acceptedCommentId, boolean resolved) {
}
