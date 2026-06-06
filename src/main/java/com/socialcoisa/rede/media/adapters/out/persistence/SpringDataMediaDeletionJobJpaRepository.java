package com.socialcoisa.rede.media.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMediaDeletionJobJpaRepository extends JpaRepository<MediaDeletionJobJpaEntity, String> {

	Optional<MediaDeletionJobJpaEntity> findByMediaId(String mediaId);

	List<MediaDeletionJobJpaEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status,
			Instant now, Pageable pageable);
}
