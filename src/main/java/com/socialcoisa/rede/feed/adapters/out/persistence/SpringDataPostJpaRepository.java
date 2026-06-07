package com.socialcoisa.rede.feed.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;

interface SpringDataPostJpaRepository extends JpaRepository<PostJpaEntity, String> {

	List<PostJpaEntity> findByIdIn(Collection<String> ids);

	List<PostJpaEntity> findByAuthorIdInAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(Collection<String> authorIds,
			PostStatus status, Instant before, Pageable pageable);

	List<PostJpaEntity> findByAuthorIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(String authorId, PostStatus status,
			Instant before, Pageable pageable);

	List<PostJpaEntity> findByCommunityIdAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(String communityId,
			PostStatus status, Instant before, Pageable pageable);

	List<PostJpaEntity> findByStatusAndCreatedAtBeforeOrderByCreatedAtDesc(PostStatus status, Instant before,
			Pageable pageable);

	List<PostJpaEntity> findByTypeAndStatusAndCreatedAtBeforeOrderByCreatedAtDesc(PostType type, PostStatus status,
			Instant before, Pageable pageable);
}
