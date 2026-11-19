package com.socialcoisa.rede.desafios.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.desafios.domain.Participacao;
import com.socialcoisa.rede.desafios.domain.ParticipacaoStatus;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@DynamicUpdate
@Table(name = "desafios_participacoes")
class ParticipacaoJpaEntity {

	@Id
	private String id;

	@Column(name = "desafio_id", nullable = false)
	private String desafioId;

	@Column(name = "author_id", nullable = false)
	private String authorId;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ParticipacaoStatus status;

	protected ParticipacaoJpaEntity() {
	}

	ParticipacaoJpaEntity(Participacao participacao) {
		this.id = participacao.id();
		this.desafioId = participacao.desafioId();
		this.authorId = participacao.authorId().value();
		this.content = participacao.content();
		this.createdAt = participacao.createdAt();
		this.status = participacao.status();
	}

	Participacao toDomain() {
		return new Participacao(id, desafioId, new com.socialcoisa.rede.feed.domain.UserId(authorId), content,
			createdAt, status);
	}
}
