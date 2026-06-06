package com.socialcoisa.rede.media.application.port.in;

import java.util.List;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public interface RequestPostMediaDeletionUseCase {

	List<String> requestPostMediaDeletion(UserId ownerId, PostId postId, List<String> mediaIds);
}
