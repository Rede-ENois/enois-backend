package com.socialcoisa.rede.media.application;

import com.socialcoisa.rede.feed.domain.UserId;

public record CreateMediaUploadCommand(UserId ownerId, String fileName, String mimeType, long size, String purpose) {
}
