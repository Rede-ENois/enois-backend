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
	 * As versões de trilha que a lista pública vai usar, **pelo par (dono, versão)**. A linha
	 * carrega html e peças em longtext, então só vêm as versões pedidas — não o histórico inteiro de
	 * quem publica muito. O rascunho nunca vira versão, então não aparece aqui.
	 *
	 * <p>Filtrar {@code userId in (...)} e {@code version in (...)} como dois conjuntos
	 * independentes devolve o produto cartesiano — o dono A com uma versão que só o dono B usa. Uma
	 * consulta por dono, com a versão dele, pede exatamente o que a lista vai ler. São poucas
	 * consultas por página (no máximo 50 donos), todas na mesma transação.</p>
	 */
	@Query("""
		select p from PortfolioPublicationJpaEntity p
		where p.userId = :userId and p.version = :version
		""")
	Optional<PortfolioPublicationJpaEntity> findPublishedVersion(@Param("userId") String userId,
			@Param("version") int version);
}
