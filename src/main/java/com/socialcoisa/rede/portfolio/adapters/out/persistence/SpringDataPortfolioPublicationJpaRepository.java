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
	 * Toda versão publicada dos donos informados, para o título da lista. O rascunho nunca vira
	 * versão, então não aparece aqui; as peças são lidas junto com a linha.
	 */
	@Query("""
		select p from PortfolioPublicationJpaEntity p
		where p.userId in :userIds and p.version > 0
		""")
	List<PortfolioPublicationJpaEntity> findPublished(@Param("userIds") Collection<String> userIds);
}
