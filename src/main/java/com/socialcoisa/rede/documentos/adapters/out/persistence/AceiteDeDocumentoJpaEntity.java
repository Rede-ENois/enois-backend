package com.socialcoisa.rede.documentos.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.documentos.domain.AceiteDeDocumento;
import com.socialcoisa.rede.documentos.domain.DocumentoTipo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Linha de `documentos_legais_aceites` (V377). Nunca alterada nem apagada pela aplicação. */
@Entity
@Table(name = "documentos_legais_aceites")
class AceiteDeDocumentoJpaEntity {

	@Id
	private String id;

	@Column(name = "documento_id", nullable = false)
	private String documentoId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "documento_tipo", nullable = false)
	private String documentoTipo;

	@Column(name = "documento_versao", nullable = false)
	private int documentoVersao;

	@Column(name = "conteudo_hash", nullable = false, length = 64)
	private String conteudoHash;

	@Column(nullable = false)
	private String origem;

	@Column(name = "aceito_em", nullable = false)
	private Instant aceitoEm;

	@Column(name = "ip_hash", length = 64)
	private String ipHash;

	@Column(name = "user_agent")
	private String userAgent;

	// `tinyint` na migração: o Hibernate esperaria INTEGER e a validação de schema derrubava o boot.
	@Column(name = "update_scope", nullable = false, columnDefinition = "tinyint")
	private int updateScope;

	protected AceiteDeDocumentoJpaEntity() {
	}

	static AceiteDeDocumentoJpaEntity from(AceiteDeDocumento aceite) {
		AceiteDeDocumentoJpaEntity entity = new AceiteDeDocumentoJpaEntity();
		entity.id = aceite.id();
		entity.documentoId = aceite.documentoId();
		entity.userId = aceite.userId();
		entity.documentoTipo = aceite.documentoTipo().name();
		entity.documentoVersao = aceite.documentoVersao();
		entity.conteudoHash = aceite.conteudoHash();
		entity.origem = aceite.origem().name();
		entity.aceitoEm = aceite.aceitoEm();
		entity.ipHash = aceite.ipHash();
		entity.userAgent = aceite.userAgent();
		entity.updateScope = 0;
		return entity;
	}

	AceiteDeDocumento toDomain() {
		return new AceiteDeDocumento(id, documentoId, userId, DocumentoTipo.valueOf(documentoTipo), documentoVersao,
			conteudoHash, AceiteDeDocumento.Origem.valueOf(origem), aceitoEm, ipHash, userAgent);
	}
}
