package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.ScheduledPost;

public interface ScheduledPostRepository {

	ScheduledPost save(ScheduledPost post);

	/** Os agendados de alguém, do mais próximo ao mais distante. */
	List<ScheduledPost> findByUser(String userId);

	Optional<ScheduledPost> findById(String id);

	Optional<ScheduledPost> findByIdForUpdate(String id);

	void delete(String id);

	/** Vencidos e ainda tentáveis (tentativas abaixo do teto), do mais antigo ao mais novo. */
	List<ScheduledPost> findDue(Instant now, int attemptsBelow, int limit);
}
