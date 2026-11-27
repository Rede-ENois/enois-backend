package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPortfolioPublicationJpaRepository extends JpaRepository<PortfolioPublicationJpaEntity, String> {

	Optional<PortfolioPublicationJpaEntity> findByUserIdAndVersion(String userId, int version);

	Optional<PortfolioPublicationJpaEntity> findFirstByUserIdOrderByVersionDesc(String userId);

	/**
	 * As versões de trilha que a lista pública vai usar, dos donos informados. A linha carrega html
	 * e peças em longtext, então só vêm as versões pedidas — não o histórico inteiro de quem publica
	 * muito. O rascunho nunca vira versão, então não aparece aqui.
	 */
	@Query("""
		select p from PortfolioPublicationJpaEntity p
		where p.userId in :userIds and p.version in :versions
		""")
	List<PortfolioPublicationJpaEntity> findPublished(@Param("userIds") Collection<String> userIds,
			@Param("versions") Collection<Integer> versions);
}
