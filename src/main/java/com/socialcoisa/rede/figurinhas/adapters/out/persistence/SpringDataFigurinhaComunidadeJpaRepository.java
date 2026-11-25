package com.socialcoisa.rede.figurinhas.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataFigurinhaComunidadeJpaRepository extends JpaRepository<FigurinhaComunidadeJpaEntity, String> {

	/** `ativo` 1 = no ar; desativada tem NULL, então este finder nunca devolve desativada. */
	Optional<FigurinhaComunidadeJpaEntity> findByCodigoAndAtivo(String codigo, Integer ativo);

	@Query("select e.id from FigurinhaComunidadeJpaEntity e where e.id in :ids and e.ativo = 1")
	List<String> findAtivosEntre(@Param("ids") Collection<String> ids);

	@Query("select e from FigurinhaComunidadeJpaEntity e where e.ativo = 1 "
		+ "order by e.criadoEm desc, e.id desc")
	List<FigurinhaComunidadeJpaEntity> findAtivas(Pageable pageable);

	/**
	 * Busca por nome, código ou id (trecho). O padrão chega em caixa baixa e `lower(...)` dos dois
	 * lados é o que lê "sapo", "SAPO" e "Sapo" como iguais em qualquer collation (H2 e MariaDB
	 * divergem). O `escape` é explícito porque cada banco tem o seu padrão de escape no LIKE, e o
	 * adaptador escapa `%`, `_` e `\` com barra invertida.
	 */
	@Query("select e from FigurinhaComunidadeJpaEntity e where e.ativo = 1 "
		+ "and (lower(e.nome) like :padrao escape '\\' or lower(e.codigo) like :padrao escape '\\' "
		+ "or lower(e.id) like :padrao escape '\\') "
		+ "order by e.criadoEm desc, e.id desc")
	List<FigurinhaComunidadeJpaEntity> buscar(@Param("padrao") String padrao, Pageable pageable);

	@Query("select count(e) from FigurinhaComunidadeJpaEntity e where e.ativo = 1")
	long contarAtivas();

	@Query("select count(e) from FigurinhaComunidadeJpaEntity e where e.ativo = 1 "
		+ "and (lower(e.nome) like :padrao escape '\\' or lower(e.codigo) like :padrao escape '\\' "
		+ "or lower(e.id) like :padrao escape '\\')")
	long contarBusca(@Param("padrao") String padrao);

	@Query("select count(e) from FigurinhaComunidadeJpaEntity e where e.autorId = :autor and e.ativo = 1")
	long contarAtivasDoAutor(@Param("autor") String autorId);
}
