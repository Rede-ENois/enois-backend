package com.socialcoisa.rede.paporeto.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * O intervalo da timeline de um assunto num escopo: quantas falas o subject teve no bucket da
 * hora. A linha é RECONCILIADA a cada corte (o corte manda a lista completa do escopo e o
 * adaptador substitui — o mesmo bucket volta ao mesmo número); é série histórica que corrige
 * conteúdo removido na passagem seguinte, sem fila de soma.
 */
@Entity
@Table(name = "papo_intervalos")
public class PapoIntervaloJpaEntity {

	@Id
	@Column(length = 200)
	private String id;

	@Column(nullable = false, length = 64)
	private String escopo;

	@Column(nullable = false, length = 120)
	private String subject;

	@Column(nullable = false)
	private Instant hora;

	@Column(nullable = false)
	private long falas;

	protected PapoIntervaloJpaEntity() {
	}

	PapoIntervaloJpaEntity(String escopo, String subject, Instant hora, long falas) {
		this.id = escopo + "@" + subject + "@" + hora;
		this.escopo = escopo;
		this.subject = subject;
		this.hora = hora;
		this.falas = falas;
	}

	String escopo() {
		return escopo;
	}

	String subject() {
		return subject;
	}

	Instant hora() {
		return hora;
	}

	long falas() {
		return falas;
	}

}
