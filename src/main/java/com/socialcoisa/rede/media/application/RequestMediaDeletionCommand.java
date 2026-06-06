package com.socialcoisa.rede.media.application;

import com.socialcoisa.rede.feed.domain.UserId;

public record RequestMediaDeletionCommand(UserId ownerId, String mediaId) {
}
