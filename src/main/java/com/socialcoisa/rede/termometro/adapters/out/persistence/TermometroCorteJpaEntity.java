package com.socialcoisa.rede.termometro.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * O corte agregado de uma janela do Termômetro. A janela é a chave primária porque a linha é
 * REESCRITA a cada corte de 5 minutos — não é histórico, é o estado atual do painel. O JSON
 * das contagens é gerado pelo domínio ({@link com.socialcoisa.rede.termometro.domain.CorteTermico}
 * em ordem fixa de enum) e relido por ele.
 */
@Entity
@Table(name = "termometro_cortes")
public class TermometroCorteJpaEntity {

	@Id
	@Column(length = 20)
	private String periodo;

	@Column(nullable = false, columnDefinition = "text")
	private String contagens;

	@Column(nullable = false)
	private long manifestacoes;

	@Column(nullable = false)
	private long participantes;

	@Column(nullable = false)
	private Instant watermark;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TermometroCorteJpaEntity() {
	}

	private TermometroCorteJpaEntity(String periodo, String contagens, long manifestacoes, long participantes,
			Instant watermark, Instant updatedAt) {
		this.periodo = periodo;
		this.contagens = contagens;
		this.manifestacoes = manifestacoes;
		this.participantes = participantes;
		this.watermark = watermark;
		this.updatedAt = updatedAt;
	}

	static TermometroCorteJpaEntity de(com.socialcoisa.rede.termometro.domain.CorteTermico corte) {
		return new TermometroCorteJpaEntity(corte.periodo().name(), corte.contagensComoJson(),
				corte.manifestacoes(), corte.participantes(), corte.watermark(), Instant.now());
	}

	com.socialcoisa.rede.termometro.domain.CorteTermico toDomain() {
		return com.socialcoisa.rede.termometro.domain.CorteTermico.de(
				com.socialcoisa.rede.termometro.domain.TermometroPeriodo.valueOf(periodo), contagens,
				manifestacoes, participantes, watermark);
	}

}
