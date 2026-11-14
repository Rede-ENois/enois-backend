package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.eroleplay.domain.CampaignStatus;

interface SpringDataCampaignJpaRepository extends JpaRepository<CampaignJpaEntity, String> {

	Optional<CampaignJpaEntity> findBySlug(String slug);

	boolean existsBySlug(String slug);

	/**
	 * Descoberta: so as campanhas ativas, com busca opcional por nome, descricao ou ambientacao.
	 *
	 * A busca trabalha APENAS com metadados publicos — participantes, mesas e o conteudo da
	 * campanha nao entram aqui, nem por acaso de juncao.
	 */
	@Query("""
		SELECT c FROM CampaignJpaEntity c
		WHERE c.status IN :statuses
		  AND c.visibility = :visibility
		  AND (:before IS NULL OR c.createdAt < :before)
		  AND (:query IS NULL OR LOWER(c.name) LIKE :query ESCAPE '!'
		       OR LOWER(c.description) LIKE :query ESCAPE '!'
		       OR LOWER(c.setting) LIKE :query ESCAPE '!')
		ORDER BY c.createdAt DESC""")
	List<CampaignJpaEntity> listVisible(@Param("statuses") List<CampaignStatus> statuses,
			@Param("visibility") com.socialcoisa.rede.eroleplay.domain.CampaignVisibility visibility,
			@Param("query") String query, @Param("before") Instant before, Pageable pageable);

	/**
	 * Ajusta o contador NO BANCO, em uma instrucao: {@code member_count = greatest(0, member_count + :delta)}.
	 *
	 * <p>Antes o ajuste lia a entidade, somava em memoria e gravava a linha inteira. Duas entradas
	 * simultaneas liam o MESMO valor e a segunda sobrescrevia o incremento da primeira. O numero
	 * aparece na listagem e ninguem recalcula, entao a campanha ficaria menor do que e para sempre.</p>
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE CampaignJpaEntity c SET c.memberCount = CASE WHEN c.memberCount + :delta < 0 THEN 0 "
		+ "ELSE c.memberCount + :delta END WHERE c.id = :campaignId")
	int adjustMemberCount(@Param("campaignId") String campaignId, @Param("delta") int delta);
}
