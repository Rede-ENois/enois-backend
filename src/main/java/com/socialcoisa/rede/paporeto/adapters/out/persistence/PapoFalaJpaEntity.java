package com.socialcoisa.rede.paporeto.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A fala crua do Papo Reto: uma expressão extraída de texto público, dentro do bucket de sua
 * hora de publicação. O texto completo não existe aqui — só a expressão normalizada que
 * nomeia a conversa. O autor vive só nesta tabela (contagem de pessoas distintas da janela) e
 * nunca sai para a página. A publicação de origem é a âncora da deduplicação e o `lidaEm` é a
 * fronteira de leitura do corte incremental.
 */
@Entity
@Table(name = "papo_falas")
public class PapoFalaJpaEntity {

	@Id
	@Column(length = 64)
	private String id;

	@Column(nullable = false)
	private Instant hora;

	/** A expressão dobrada (sem acento, minúscula), o subject da conversa. */
	@Column(nullable = false, length = 120)
	private String expressao;

	@Column(nullable = false, length = 20)
	private String fonte;

	@Column(name = "autor_id", nullable = false, length = 64)
	private String autorId;

	@Column(name = "community_id", length = 64)
	private String communityId;

	/** A publicação que gerou a fala: lida de novo, ela não fala duas vezes. */
	@Column(name = "publicacao_id", nullable = false, length = 64)
	private String publicacaoId;

	@Column(name = "lida_em", nullable = false)
	private Instant lidaEm;

	protected PapoFalaJpaEntity() {
	}

	PapoFalaJpaEntity(String id, Instant hora, String expressao, String fonte, String autorId, String communityId,
			String publicacaoId, Instant lidaEm) {
		this.id = id;
		this.hora = hora;
		this.expressao = expressao;
		this.fonte = fonte;
		this.autorId = autorId;
		this.communityId = communityId;
		this.publicacaoId = publicacaoId;
		this.lidaEm = lidaEm;
	}

	Instant hora() {
		return hora;
	}

	String expressao() {
		return expressao;
	}

	String fonte() {
		return fonte;
	}

	String autorId() {
		return autorId;
	}

	String communityId() {
		return communityId;
	}

	String publicacaoId() {
		return publicacaoId;
	}

	Instant lidaEm() {
		return lidaEm;
	}

}
