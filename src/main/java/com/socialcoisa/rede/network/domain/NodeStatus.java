package com.socialcoisa.rede.network.domain;

/**
 * Estados de um nó da rede.
 *
 * <p>REVOKED é terminal de propósito: revogação que pode ser desfeita não é revogação. Voltar à
 * rede exige cadastro novo — identidade nova, chave nova.</p>
 */
public enum NodeStatus {

	/** Enroll feito, aguardando autorização humana. Nenhum acesso além do heartbeat é recusado. */
	PENDING_APPROVAL,

	/** Aprovado e operando. */
	ACTIVE,

	/** Suspenso por decisão administrativa; recuperável. */
	SUSPENDED,

	/** Isolado por anomalia ou por decisão administrativa; análise antes de voltar. */
	QUARANTINED,

	/** Revogado. Terminal. */
	REVOKED;

	/** Só ACTIVE executa tarefas. Estados administrativos podem ler o próprio estado. */
	public boolean canOperate() {
		return this == ACTIVE;
	}
}
