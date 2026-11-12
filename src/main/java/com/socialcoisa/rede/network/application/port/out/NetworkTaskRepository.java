package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.DataClass;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * Persistência das tarefas distribuídas. Só o Control Plane toca aqui; nenhum nó externo tem
 * acesso a esta tabela (ADR-005).
 *
 * <p>As transições são condicionais de propósito: {@code claim} e {@code complete} devolvem quantas
 * linhas mudaram, e zero significa "outra rodada chegou primeiro". Ler e depois gravar deixaria uma
 * janela em que duas instâncias designam a mesma tarefa.</p>
 */
public interface NetworkTaskRepository {

	Optional<NetworkTask> findById(String id);

	/** Tarefa já criada com esta chave de idempotência, se houver. */
	Optional<NetworkTask> findByIdempotencyKey(String idempotencyKey);

	/** Cria a tarefa em PENDING. Idempotente pela chave: a mesma chave não cria duas tarefas. */
	void create(String id, TaskType type, String idempotencyKey, String payload, DataClass dataClass,
			Instant deadline, Instant nextAttemptAt, Instant now);

	/** Tarefas na fila, elegíveis agora, mais antigas primeiro. */
	List<NetworkTask> findDispatchable(Instant now, int limit);

	/** Tarefas designadas cujo claim venceu: o nó caiu no meio. */
	List<NetworkTask> findAbandoned(Instant now, int limit);

	/** Tarefas designadas e ainda dentro do claim, para o nó buscar o que é dele. */
	List<NetworkTask> findAssignedTo(String nodeId, Instant now, int limit);

	/**
	 * Tarefas designadas a nós que perderam o direito de operar (suspenso, em quarentena, revogado),
	 * com claim ainda vigente.
	 *
	 * <p>É o insumo da drenagem: ler só o claim vencido deixaria a tarefa esperando um nó que já não
	 * autentica, e ela só sairia quando o prazo vencesse — dois minutos por padrão.</p>
	 */
	List<NetworkTask> findOnNonOperatingNodes(Instant now, int limit);

	/** Quantas tarefas estão nessa situação — o número do painel, sem paginar. */
	long countOnNonOperatingNodes(Instant now);

	/**
	 * Designa a tarefa a um nó, subindo a tentativa. Condicional: só muda se a tarefa ainda estiver
	 * PENDING e a tentativa esperada for a atual.
	 *
	 * @return 1 quando esta rodada designou; 0 quando outra chegou primeiro
	 */
	int claim(String id, String nodeId, int expectedAttempt, Instant claimExpiresAt, Instant now);

	/** Conclui a tarefa. Condicional por tentativa: resultado de tentativa antiga não conclui. */
	int complete(String id, int attempt, String output, Instant now);

	/** Marca falha definitiva (erro não retentável). */
	int fail(String id, int attempt, String error, Instant now);

	/** Devolve à fila para nova tentativa, com o próximo instante calculado pelo chamador. */
	int requeue(String id, int attempt, String error, Instant nextAttemptAt, Instant now);

	/** Esgota a tarefa: vira DEAD (DLQ) com o último erro. */
	int kill(String id, int attempt, String error, Instant now);

	/** Cancela por decisão administrativa. */
	int cancel(String id, String actorId, Instant now);

	long countByStatus(TaskStatus status);

	/** Tarefas terminais recentes, para o painel mostrar a DLQ e o histórico. */
	List<NetworkTask> listRecent(int limit);
}
