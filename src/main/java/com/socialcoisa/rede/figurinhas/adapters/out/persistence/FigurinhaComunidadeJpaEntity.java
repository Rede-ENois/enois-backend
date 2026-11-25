package com.socialcoisa.rede.figurinhas.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidade;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Linha da tabela `figurinhas_comunidade` (V376).
 *
 * <p>A coluna {@code ativo} é 1 enquanto a figurinha está no ar e NULL depois de desativada: é o
 * que faz a chave única {@code (codigo, ativo)} permitir o código ser reusado por outra pessoa
 * depois de uma desativação, sem nunca permitir duas ativas iguais.</p>
 */
@Entity
@Table(name = "figurinhas_comunidade")
class FigurinhaComunidadeJpaEntity {

	static final int ATIVA = 1;

	@Id
	private String id;

	@Column(nullable = false)
	private String codigo;

	@Column(name = "codigo_normalizado", nullable = false)
	private String codigoNormalizado;

	@Column(nullable = false)
	private String nome;

	@Column(name = "autor_id", nullable = false)
	private String autorId;

	@Column(name = "media_id", nullable = false)
	private String mediaId;

	@Column(name = "image_url", nullable = false)
	private String imageUrl;

	@Column(name = "mime_type", nullable = false)
	private String mimeType;

	@Column(nullable = false)
	private int largura;

	@Column(nullable = false)
	private int altura;

	@Column(nullable = false)
	private int bytes;

	/** 1 enquanto ativa; NULL depois de desativar (é a metade da chave única das ativas). */
	@Column
	private Integer ativo;

	@Column(name = "update_scope", nullable = false)
	private int updateScope;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "atualizado_em", nullable = false)
	private Instant atualizadoEm;

	@Column(name = "desativado_em")
	private Instant desativadoEm;

	@Column(name = "desativado_por")
	private String desativadoPor;

	@Column(name = "motivo_desativacao")
	private String motivoDesativacao;

	protected FigurinhaComunidadeJpaEntity() {
	}

	static FigurinhaComunidadeJpaEntity from(FigurinhaComunidade figurinha) {
		FigurinhaComunidadeJpaEntity entity = new FigurinhaComunidadeJpaEntity();
		entity.id = figurinha.id();
		entity.codigo = figurinha.codigo();
		entity.codigoNormalizado = figurinha.codigoNormalizado();
		entity.nome = figurinha.nome();
		entity.autorId = figurinha.autorId();
		entity.mediaId = figurinha.mediaId();
		entity.imageUrl = figurinha.imageUrl();
		entity.mimeType = figurinha.mimeType();
		entity.largura = figurinha.largura();
		entity.altura = figurinha.altura();
		entity.bytes = figurinha.bytes();
		entity.criadoEm = figurinha.criadoEm();
		entity.aplicarEstado(figurinha);
		return entity;
	}

	/** Copia o que muda depois do cadastro (desativação). O resto da linha é imutável. */
	void aplicarEstado(FigurinhaComunidade figurinha) {
		ativo = figurinha.ativa() ? ATIVA : null;
		// Coluna só para a chave única e o índice não dependerem de valor NULL.
		updateScope = 0;
		atualizadoEm = figurinha.atualizadoEm();
		desativadoEm = figurinha.desativadoEm();
		desativadoPor = figurinha.desativadoPor();
		motivoDesativacao = figurinha.motivoDesativacao();
	}

	FigurinhaComunidade toDomain() {
		return new FigurinhaComunidade(id, codigo, codigoNormalizado, nome, autorId, mediaId, imageUrl, mimeType,
			largura, altura, bytes, ativo != null, criadoEm, atualizadoEm, desativadoEm, desativadoPor,
			motivoDesativacao);
	}
}
