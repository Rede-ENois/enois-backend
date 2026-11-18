package com.socialcoisa.rede.paporeto.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * O agregado atual de um assunto do Papo Reto num escopo. (escopo, subject) é a chave e a
 * linha é REESCRITA a cada corte de 5 minutos — não é histórico, é o estado do ranking. O
 * JSON do estado é gerado pelo domínio
 * ({@link com.socialcoisa.rede.paporeto.domain.PapoAssunto}) e relido por ele.
 */
@Entity
@Table(name = "papo_assuntos")
@IdClass(PapoAssuntoJpaEntity.Chave.class)
public class PapoAssuntoJpaEntity {

	/** A chave composta do agregado: o escopo (vazio é o global) e o subject. */
	static class Chave {

		private String escopo;

		private String subject;

		protected Chave() {
		}

		Chave(String escopo, String subject) {
			this.escopo = escopo;
			this.subject = subject;
		}

		@Override
		public boolean equals(Object outro) {
			if (!(outro instanceof Chave chave)) {
				return false;
			}
			return escopo.equals(chave.escopo) && subject.equals(chave.subject);
		}

		@Override
		public int hashCode() {
			return escopo.hashCode() * 31 + subject.hashCode();
		}

	}

	@Id
	@Column(length = 64)
	private String escopo;

	@Id
	@Column(length = 120)
	private String subject;

	@Column(nullable = false, columnDefinition = "text")
	private String estado;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PapoAssuntoJpaEntity() {
	}

	private PapoAssuntoJpaEntity(String escopo, String subject, String estado, Instant updatedAt) {
		this.escopo = escopo;
		this.subject = subject;
		this.estado = estado;
		this.updatedAt = updatedAt;
	}

	static PapoAssuntoJpaEntity de(String escopo, com.socialcoisa.rede.paporeto.domain.PapoAssunto assunto) {
		return new PapoAssuntoJpaEntity(escopo, assunto.subject(), assunto.estadoComoJson(), Instant.now());
	}

	com.socialcoisa.rede.paporeto.domain.PapoAssunto toDomain() {
		return com.socialcoisa.rede.paporeto.domain.PapoAssunto.de(subject, estado);
	}

}
