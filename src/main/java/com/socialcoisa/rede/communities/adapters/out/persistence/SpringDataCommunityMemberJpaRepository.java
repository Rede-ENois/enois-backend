package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCommunityMemberJpaRepository extends JpaRepository<CommunityMemberJpaEntity, Long> {

	boolean existsByCommunityIdAndUserId(String communityId, String userId);

	void deleteByCommunityIdAndUserId(String communityId, String userId);

	void deleteByCommunityId(String communityId);

	int countByCommunityId(String communityId);

	List<CommunityMemberJpaEntity> findByCommunityId(String communityId);

	List<CommunityMemberJpaEntity> findByUserId(String userId);

	Optional<CommunityMemberJpaEntity> findByCommunityIdAndUserId(String communityId, String userId);

	/** Contagem de membros de VARIAS comunidades numa consulta (a vitrine fazia uma por item). */
	@Query("select m.communityId, count(m) from CommunityMemberJpaEntity m"
			+ " where m.communityId in :communityIds group by m.communityId")
	List<Object[]> countByCommunityIds(@Param("communityIds") Collection<String> communityIds);

	/** Em quais dessas comunidades a pessoa esta (o "sou membro" da vitrine, em lote). */
	@Query("select m.communityId from CommunityMemberJpaEntity m"
			+ " where m.userId = :userId and m.communityId in :communityIds")
	List<String> findCommunityIdsByUserIdAndCommunityIdsIn(@Param("userId") String userId,
			@Param("communityIds") Collection<String> communityIds);

}
