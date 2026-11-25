package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A linha de {@code wrapped_retrospectivas}: a retrospectiva de UMA conta em UM ano, com o estado
 * de geração e o resultado final.
 *
 * <p>O resultado é gravado aqui UMA vez, quando a conta termina (DONE); a leitura da tela não
 * consulta nada analítico. Colunas de controle (claim, falha, tentativas) são escritas por
 * {@link SpringDataWrappedJpaRepository} em consultas próprias, não por salvar a entidade — a
 * mesma regra de linha quente do feed (ver docs/CONCURRENCY.md).</p>
 */
@Entity
@IdClass(WrappedRetrospectivaId.class)
@Table(name = "wrapped_retrospectivas")
class WrappedRetrospectivaJpaEntity {

	@Id
	@Column(name = "user_id", length = 64, nullable = false)
	private String userId;

	@Id
	@Column(nullable = false)
	private int ano;

	@Column(nullable = false, length = 16)
	private String estado;

	@Column(nullable = false)
	private long posicao;

	@Column(nullable = false)
	private int prioridade;

	@Column(name = "algoritmo_versao", nullable = false)
	private int algoritmoVersao;

	@Column(name = "dados_insuficientes", nullable = false)
	private boolean dadosInsuficientes;

	@Column(columnDefinition = "longtext")
	private String resultado;

	@Column(length = 2000)
	private String falha;

	@Column(nullable = false)
	private int tentativas;

	@Column(name = "claim_expira_em")
	private Instant claimExpiraEm;

	@Column(name = "iniciado_em")
	private Instant iniciadoEm;

	@Column(name = "concluido_em")
	private Instant concluidoEm;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected WrappedRetrospectivaJpaEntity() {
	}

	static WrappedRetrospectivaJpaEntity nova(String userId, int ano, long posicao, int prioridade,
			int algoritmoVersao, Instant agora) {
		WrappedRetrospectivaJpaEntity e = new WrappedRetrospectivaJpaEntity();
		e.userId = userId;
		e.ano = ano;
		e.estado = "PENDING";
		e.posicao = posicao;
		e.prioridade = prioridade;
		e.algoritmoVersao = algoritmoVersao;
		e.dadosInsuficientes = false;
		e.tentativas = 0;
		e.criadoEm = agora;
		e.updatedAt = agora;
		return e;
	}

	String userId() {
		return userId;
	}

	int ano() {
		return ano;
	}

	String estado() {
		return estado;
	}

	long posicao() {
		return posicao;
	}

	int prioridade() {
		return prioridade;
	}

	int algoritmoVersao() {
		return algoritmoVersao;
	}

	boolean dadosInsuficientes() {
		return dadosInsuficientes;
	}

	String resultado() {
		return resultado;
	}

	String falha() {
		return falha;
	}

	int tentativas() {
		return tentativas;
	}

	Instant claimExpiraEm() {
		return claimExpiraEm;
	}

	Instant iniciadoEm() {
		return iniciadoEm;
	}

	Instant concluidoEm() {
		return concluidoEm;
	}

	Instant criadoEm() {
		return criadoEm;
	}

	Instant updatedAt() {
		return updatedAt;
	}
}
