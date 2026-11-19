package com.socialcoisa.rede.network.domain;

/**
 * Estados de um nó da rede.
 *
 * <p>REVOKED é terminal de propósito: revogação que pode ser desfeita não é revogação. Voltar à
 * rede exige cadastro novo — identidade nova, chave nova.</p>
 *
 * <p>SUSPENDED e PAUSED parecem o mesmo estado e não são: desligar o servidor e parar de emprestar
 * a máquina são decisões diferentes. Na suspensão ele sai da rede e as chaves caem; na pausa ele
 * continua lá (autentica, mede, reporta) e só deixa de receber trabalho — e volta sozinho. Sem a
 * distinção, pausar era suspender, e quem pausava voltava a operar só quando um admin reaprovava.</p>
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

	/**
	 * Pausado pelo próprio participante (brief §18, "pausar participação"). Reversível por ele
	 * mesmo: mantém a chave, o heartbeat e as medições, e não recebe tarefa nova. Diferente de
	 * SUSPENDED, que é saída da rede com as chaves revogadas e volta só por decisão administrativa.
	 */
	PAUSED,

	/** Revogado. Terminal. */
	REVOKED;

	/** Só ACTIVE executa tarefas. Estados administrativos podem ler o próprio estado. */
	public boolean canOperate() {
		return this == ACTIVE;
	}
}
