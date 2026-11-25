package com.socialcoisa.rede.figurinhas.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidadeEvento;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Linha da tabela `figurinhas_comunidade_eventos` (V376): o histórico da figurinha. */
@Entity
@Table(name = "figurinhas_comunidade_eventos")
class FigurinhaEventoJpaEntity {

	@Id
	private String id;

	@Column(name = "figurinha_id", nullable = false)
	private String figurinhaId;

	@Column(nullable = false)
	private String tipo;

	@Column(name = "ator_id")
	private String atorId;

	@Column
	private String detalhe;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	protected FigurinhaEventoJpaEntity() {
	}

	static FigurinhaEventoJpaEntity from(FigurinhaComunidadeEvento evento) {
		FigurinhaEventoJpaEntity entity = new FigurinhaEventoJpaEntity();
		entity.id = evento.id();
		entity.figurinhaId = evento.figurinhaId();
		entity.tipo = evento.tipo();
		entity.atorId = evento.atorId();
		entity.detalhe = evento.detalhe();
		entity.criadoEm = evento.criadoEm();
		return entity;
	}

	FigurinhaComunidadeEvento toDomain() {
		return new FigurinhaComunidadeEvento(id, figurinhaId, tipo, atorId, detalhe, criadoEm);
	}
}
