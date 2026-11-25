package com.socialcoisa.rede.documentos.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataDocumentoLegalJpaRepository extends JpaRepository<DocumentoLegalJpaEntity, String> {

	/** A versão em vigor é a aprovada com vigência aberta. */
	@Query("select d from DocumentoLegalJpaEntity d where d.tipo in :tipos and d.aprovado = true "
		+ "and d.vigenteDesde is not null")
	List<DocumentoLegalJpaEntity> vigentes(@Param("tipos") Collection<String> tipos);

	@Query("select d from DocumentoLegalJpaEntity d where d.tipo in :tipos and d.aprovado = true "
		+ "and d.vigenteDesde is null")
	List<DocumentoLegalJpaEntity> emRevisao(@Param("tipos") Collection<String> tipos);

	Optional<DocumentoLegalJpaEntity> findByTipoAndVersao(String tipo, int versao);

	@Query("select coalesce(max(d.versao), 0) from DocumentoLegalJpaEntity d where d.tipo = :tipo")
	int ultimaVersao(@Param("tipo") String tipo);

	List<DocumentoLegalJpaEntity> findByTipoOrderByVersaoDesc(String tipo);

	@Modifying
	@Query("update DocumentoLegalJpaEntity d set d.vigenteDesde = null where d.tipo = :tipo "
		+ "and d.vigenteDesde is not null")
	int encerrarVigencia(@Param("tipo") String tipo);
}
