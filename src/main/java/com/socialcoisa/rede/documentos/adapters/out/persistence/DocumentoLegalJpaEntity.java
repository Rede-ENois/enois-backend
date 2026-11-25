package com.socialcoisa.rede.documentos.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.documentos.domain.DocumentoLegal;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Linha de `documentos_legais` (V377). O conteúdo de uma versão nunca é alterado. */
@Entity
@Table(name = "documentos_legais")
class DocumentoLegalJpaEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String tipo;

	@Column(nullable = false)
	private int versao;

	@Column(nullable = false)
	private String titulo;

	@Column
	private String resumo;

	@Column(nullable = false, columnDefinition = "mediumtext")
	private String conteudo;

	@Column(name = "conteudo_hash", nullable = false, length = 64)
	private String conteudoHash;

	@Column(name = "exige_aceite", nullable = false)
	private boolean exigeAceite;

	@Column(nullable = false)
	private boolean aprovado;

	@Column(name = "vigente_desde")
	private Instant vigenteDesde;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "criado_por")
	private String criadoPor;

	@Column(name = "aprovado_em")
	private Instant aprovadoEm;

	@Column(name = "aprovado_por")
	private String aprovadoPor;

	protected DocumentoLegalJpaEntity() {
	}

	static DocumentoLegalJpaEntity from(DocumentoLegal documento) {
		DocumentoLegalJpaEntity entity = new DocumentoLegalJpaEntity();
		entity.id = documento.id();
		entity.aplicar(documento);
		return entity;
	}

	/** Só o estado muda (aprovação e vigência); texto, tipo e versão são imutáveis. */
	void aplicar(DocumentoLegal documento) {
		tipo = documento.tipo().name();
		versao = documento.versao();
		titulo = documento.titulo();
		resumo = documento.resumo();
		conteudo = documento.conteudo();
		conteudoHash = documento.conteudoHash();
		exigeAceite = documento.exigeAceite();
		aprovado = documento.aprovado();
		vigenteDesde = documento.vigenteDesde();
		criadoEm = documento.criadoEm();
		criadoPor = documento.criadoPor();
		aprovadoEm = documento.aprovadoEm();
		aprovadoPor = documento.aprovadoPor();
	}

	void encerrarVigencia() {
		vigenteDesde = null;
	}

	DocumentoLegal toDomain() {
		return new DocumentoLegal(id, DocumentoTipo.valueOf(tipo), versao, titulo, resumo, conteudo, conteudoHash,
			exigeAceite, aprovado, vigenteDesde, criadoEm, criadoPor, aprovadoEm, aprovadoPor);
	}
}
