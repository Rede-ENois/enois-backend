package com.socialcoisa.rede.communityprojects.adapters.out.persistence;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.communityprojects.domain.CommunityProjectStatus;

import jakarta.persistence.LockModeType;

interface SpringDataCommunityProjectJpaRepository extends JpaRepository<CommunityProjectJpaEntity, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from CommunityProjectJpaEntity p where p.id = :id")
	java.util.Optional<CommunityProjectJpaEntity> findByIdForUpdate(@Param("id") String id);

	java.util.Optional<CommunityProjectJpaEntity> findByUserIdAndSlug(String userId, String slug);

	List<CommunityProjectJpaEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

	List<CommunityProjectJpaEntity> findByUserIdAndStatusOrderByPublishedAtDesc(String userId, CommunityProjectStatus status,
			Pageable pageable);

	List<CommunityProjectJpaEntity> findByStatusOrderBySubmittedAtAsc(CommunityProjectStatus status, Pageable pageable);

	boolean existsByUserIdAndSlug(String userId, String slug);

	@Query("""
		select p from CommunityProjectJpaEntity p where p.status = :status
		and (:q is null or lower(p.title) like :q escape '!'
			or lower(coalesce(p.description, '')) like :q escape '!'
			or lower(coalesce(p.tags, '')) like :q escape '!')
		order by p.publishedAt desc
		""")
	List<CommunityProjectJpaEntity> searchRecent(@Param("status") CommunityProjectStatus status, @Param("q") String query,
			Pageable pageable);

	@Query("""
		select p from CommunityProjectJpaEntity p where p.status = :status
		and (:q is null or lower(p.title) like :q escape '!'
			or lower(coalesce(p.description, '')) like :q escape '!'
			or lower(coalesce(p.tags, '')) like :q escape '!')
		order by lower(p.title) asc
		""")
	List<CommunityProjectJpaEntity> searchByTitle(@Param("status") CommunityProjectStatus status, @Param("q") String query,
			Pageable pageable);
}
