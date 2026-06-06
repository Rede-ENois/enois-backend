package com.socialcoisa.rede.media.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.domain.MediaDeletionJob;

public interface MediaDeletionJobRepository {

	void request(String id, String mediaId, PostId postId, UserId ownerId, Instant createdAt);

	Optional<String> findStatusByMediaId(String mediaId);

	List<MediaDeletionJob> findPending(Instant now, int limit);

	void complete(String mediaId, Instant processedAt);

	void fail(String mediaId, String errorMessage, Instant nextAttemptAt);
}
