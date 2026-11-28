package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataPortfolioPublicationJpaRepository extends JpaRepository<PortfolioPublicationJpaEntity, String> {

	Optional<PortfolioPublicationJpaEntity> findByUserIdAndVersion(String userId, int version);

	Optional<PortfolioPublicationJpaEntity> findFirstByUserIdOrderByVersionDesc(String userId);

	/**
	 * A versão publicada de UM dono, pelo número dela — o par (dono, versão) que a página lê.
	 * Filtrar donos e versões como dois conjuntos soltos carrega o histórico inteiro de cada dono
	 * (html e peças são {@code longtext}) para usar uma linha. O rascunho nunca vira versão.
	 */
	@Query("""
		select p from PortfolioPublicationJpaEntity p
		where p.userId = :userId and p.version = :version
		""")
	Optional<PortfolioPublicationJpaEntity> findPublishedVersion(@Param("userId") String userId,
			@Param("version") int version);
}
