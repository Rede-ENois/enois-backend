package com.socialcoisa.rede.network.domain;

/**
 * Estado de uma tarefa distribuída.
 *
 * <p>{@code DEAD} é a DLQ: a tarefa esgotou as tentativas e não volta sozinha. Ela continua na
 * tabela, com o último erro, para aparecer no painel — apagar seria esconder o problema.</p>
 */
public enum TaskStatus {

	/** Na fila, esperando designação. */
	PENDING,

	/** Designada a um nó; o nó pode ou não ter buscado. */
	DISPATCHED,

	/** Concluída com sucesso. Terminal. */
	DONE,

	/** Falhou de forma definitiva (erro não retentável). Terminal. */
	FAILED,

	/** Esgotou as tentativas ou venceu o prazo. Terminal, visível como DLQ. */
	DEAD,

	/** Cancelada por um administrador. Terminal. */
	CANCELLED;

	public boolean terminal() {
		return this == DONE || this == FAILED || this == DEAD || this == CANCELLED;
	}
}
