package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCommunityJpaRepository extends JpaRepository<CommunityJpaEntity, String> {

	// `:q` null disables the search; it is a LikePattern (escaped, lower-cased) matched on name and description.
	@Query("SELECT c FROM CommunityJpaEntity c WHERE (:q IS NULL OR LOWER(c.name) LIKE :q ESCAPE '!' "
			+ "OR LOWER(c.description) LIKE :q ESCAPE '!') "
			+ "AND (:state IS NULL OR c.state = :state) AND (:cityKey IS NULL OR c.cityKey = :cityKey) "
			+ "AND (:before IS NULL OR c.createdAt < :before) ORDER BY c.createdAt DESC")
	List<CommunityJpaEntity> findAllBefore(@Param("q") String q, @Param("state") String state,
			@Param("cityKey") String cityKey, @Param("before") Instant before, Pageable pageable);

	@Query("SELECT c FROM CommunityJpaEntity c WHERE c.topic = :topic "
			+ "AND (:q IS NULL OR LOWER(c.name) LIKE :q ESCAPE '!' "
			+ "OR LOWER(c.description) LIKE :q ESCAPE '!') "
			+ "AND (:state IS NULL OR c.state = :state) AND (:cityKey IS NULL OR c.cityKey = :cityKey) "
			+ "AND (:before IS NULL OR c.createdAt < :before) ORDER BY c.createdAt DESC")
	List<CommunityJpaEntity> findByTopic(@Param("topic") String topic, @Param("q") String q,
			@Param("state") String state, @Param("cityKey") String cityKey, @Param("before") Instant before,
			Pageable pageable);

	/**
	 * Uma página da réplica de leitura, por chave (criado em, id) — nunca por posição, porque a
	 * réplica percorre o catálogo enquanto comunidades nascem e são apagadas.
	 */
	@Query("SELECT new com.socialcoisa.rede.communities.application.port.out.ReplicaCommunityKey("
			+ "c.id, c.createdAt, c.createdBy) FROM CommunityJpaEntity c "
			+ "WHERE (:afterCreatedAt IS NULL OR c.createdAt > :afterCreatedAt "
			+ "OR (c.createdAt = :afterCreatedAt AND c.id > :afterId)) "
			+ "ORDER BY c.createdAt, c.id")
	List<com.socialcoisa.rede.communities.application.port.out.ReplicaCommunityKey> replicaPage(
			@Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") String afterId, Pageable pageable);

	// Local communities: same city first, then the rest of the state, each by member count.
	@Query("SELECT c FROM CommunityJpaEntity c WHERE c.state = :state "
			+ "ORDER BY CASE WHEN c.cityKey = :cityKey THEN 0 ELSE 1 END, "
			+ "(SELECT COUNT(m) FROM CommunityMemberJpaEntity m WHERE m.communityId = c.id) DESC, c.createdAt DESC")
	List<CommunityJpaEntity> findLocal(@Param("state") String state, @Param("cityKey") String cityKey,
			Pageable pageable);

}
