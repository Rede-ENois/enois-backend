package com.socialcoisa.rede.termometro.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A fatia crua do Termômetro: uma manifestação classificada, dentro do bucket de sua hora de
 * publicação. O texto não existe aqui — só a emoção que o léxico deu a ele. O autor vive só
 * nesta tabela, para a contagem de pessoas distintas da janela, e nunca sai para o painel.
 * O `lidaEm` é a fronteira de leitura do corte incremental (o `hora` é o bucket, não o agora).
 */
@Entity
@Table(name = "termometro_fatias")
public class TermometroFatiaJpaEntity {

	@Id
	@Column(length = 64)
	private String id;

	@Column(nullable = false)
	private Instant hora;

	@Column(nullable = false, length = 20)
	private String fonte;

	@Column(nullable = false, length = 20)
	private String emocao;

	@Column(name = "autor_id", nullable = false)
	private String autorId;

	@Column(name = "lida_em", nullable = false)
	private Instant lidaEm;

	protected TermometroFatiaJpaEntity() {
	}

	TermometroFatiaJpaEntity(String id, Instant hora, String fonte, String emocao, String autorId,
			Instant lidaEm) {
		this.id = id;
		this.hora = hora;
		this.fonte = fonte;
		this.emocao = emocao;
		this.autorId = autorId;
		this.lidaEm = lidaEm;
	}

	Instant hora() {
		return hora;
	}

	String fonte() {
		return fonte;
	}

	String emocao() {
		return emocao;
	}

	String autorId() {
		return autorId;
	}

	Instant lidaEm() {
		return lidaEm;
	}

}
