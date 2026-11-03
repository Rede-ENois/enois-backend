package com.socialcoisa.rede.messaging.application;

import java.util.Collection;

/**
 * Something visible in a conversation changed (new message, read receipt, reaction). Published
 * inside the write transaction and delivered to the open message streams only after it commits,
 * so a client that refetches on the signal never reads the state from before the change.
 */
public record ConversationChanged(String conversationId, Collection<String> userIds) {
}
