package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.eroleplay.domain.CampaignStatus;

interface SpringDataCampaignMemberJpaRepository extends JpaRepository<CampaignMemberJpaEntity, String> {

	Optional<CampaignMemberJpaEntity> findByCampaignIdAndUserId(String campaignId, String userId);

	List<CampaignMemberJpaEntity> findByCampaignId(String campaignId);

	/**
	 * As campanhas de quem participa, com o papel, numa consulta só: a campanha vem junto do
	 * membro. Buscar a campanha depois (um por linha) faria N+1 na listagem "minhas campanhas".
	 */
	@Query("""
		SELECT m, c FROM CampaignMemberJpaEntity m, CampaignJpaEntity c
		WHERE m.campaignId = c.id
		  AND m.userId = :userId
		  AND c.status IN :statuses
		ORDER BY m.joinedAt DESC""")
	List<Object[]> findMemberships(@Param("userId") String userId,
			@Param("statuses") List<CampaignStatus> statuses, Pageable pageable);

	@Query("SELECT m.campaignId, COUNT(m) FROM CampaignMemberJpaEntity m WHERE m.campaignId IN :ids GROUP BY m.campaignId")
	List<Object[]> countByCampaignIds(@Param("ids") java.util.Collection<String> ids);

	long countByCampaignId(String campaignId);
}
