package com.socialcoisa.rede.media.application.port.out;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;

public interface MediaDeletionJobRepository {

	void request(String id, String mediaId, PostId postId, UserId ownerId, Instant createdAt);

	Optional<String> findStatusByMediaId(String mediaId);
}
