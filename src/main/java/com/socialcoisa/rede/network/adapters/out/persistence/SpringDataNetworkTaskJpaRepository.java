package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataNetworkTaskJpaRepository extends JpaRepository<NetworkTaskJpaEntity, String> {

	/**
	 * A fila do despacho. A ordem é {@code next_attempt_at} e não {@code created_at} por dois
	 * motivos, e o segundo é de banco: (1) é a ordem certa — tarefa que voltou para a fila com
	 * backoff não deve furar a frente de quem já esperava; (2) é a segunda coluna de
	 * {@code idx_network_tasks_pending}, então a consulta para no limite. Ordenando por
	 * {@code created_at} o MariaDB não usa o índice para ordenar e lê a fila inteira: medido em
	 * 11.4 com 200 mil pendentes, 200.001 entradas de índice para devolver 50 — contra 49 na
	 * ordem correta.
	 */
	@Query("select t from NetworkTaskJpaEntity t where t.status = 'PENDING' and t.nextAttemptAt <= :now "
			+ "and t.deadline > :now order by t.nextAttemptAt asc")
	List<NetworkTaskJpaEntity> findDispatchable(@Param("now") Instant now, PageRequest page);

	@Query("select t from NetworkTaskJpaEntity t where t.status = 'DISPATCHED' and t.claimExpiresAt <= :now "
			+ "order by t.claimExpiresAt asc")
	List<NetworkTaskJpaEntity> findAbandoned(@Param("now") Instant now, PageRequest page);

	@Query("select t from NetworkTaskJpaEntity t where t.status = 'DISPATCHED' and t.assignedNodeId = :nodeId "
			+ "and t.claimExpiresAt > :now order by t.createdAt asc")
	List<NetworkTaskJpaEntity> findAssignedTo(@Param("nodeId") String nodeId, @Param("now") Instant now,
			PageRequest page);

	/**
	 * Tarefas de nós que perderam o direito de operar (suspenso, em quarentena, revogado), com claim
	 * ainda vigente.
	 *
	 * <p>O join com {@code network_nodes} é o que define o que drenar: o critério é o status do nó,
	 * não o tempo. {@code findAbandoned} só vê claim <b>vencido</b>, e o nó suspenso não vai responder
	 * heartbeat até lá — a tarefa esperaria o prazo inteiro por um resultado que não vem.</p>
	 *
	 * <p>{@code status <> 'ACTIVE'} também pega {@code PENDING_APPROVAL}, o que é correto: nó não
	 * aprovado não executa, e uma tarefa designada a ele (possível se ele foi suspenso depois da
	 * designação e voltou a pendente) também precisa sair.</p>
	 */
	@Query("select t from NetworkTaskJpaEntity t join NetworkNodeJpaEntity n on n.id = t.assignedNodeId "
			+ "where t.status = 'DISPATCHED' and t.claimExpiresAt > :now and n.status <> 'ACTIVE' "
			+ "order by t.createdAt asc")
	List<NetworkTaskJpaEntity> findOnNonOperatingNodes(@Param("now") Instant now, PageRequest page);

	@Query("select count(*) from NetworkTaskJpaEntity t join NetworkNodeJpaEntity n on n.id = t.assignedNodeId "
			+ "where t.status = 'DISPATCHED' and t.claimExpiresAt > :now and n.status <> 'ACTIVE'")
	long countOnNonOperatingNodes(@Param("now") Instant now);

	@Query("select t from NetworkTaskJpaEntity t order by t.createdAt desc")
	List<NetworkTaskJpaEntity> listRecent(PageRequest page);

	long countByStatus(String status);

	java.util.Optional<NetworkTaskJpaEntity> findByIdempotencyKey(String idempotencyKey);

	/**
	 * Designa a tarefa. O {@code status = 'PENDING'} e o {@code attempt} esperado no WHERE são a
	 * trava: duas instâncias que leram a mesma linha não designam as duas.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update NetworkTaskJpaEntity t set t.status = 'DISPATCHED', t.assignedNodeId = :nodeId, "
			+ "t.attempt = t.attempt + 1, t.dispatchedAt = :now, t.claimExpiresAt = :claimExpiresAt, "
			+ "t.updatedAt = :now where t.id = :id and t.status = 'PENDING' and t.attempt = :expectedAttempt")
	int claim(@Param("id") String id, @Param("nodeId") String nodeId, @Param("expectedAttempt") int expectedAttempt,
			@Param("claimExpiresAt") Instant claimExpiresAt, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update NetworkTaskJpaEntity t set t.status = 'DONE', t.output = :output, t.finishedAt = :now, "
			+ "t.claimExpiresAt = null, t.updatedAt = :now where t.id = :id and t.status = 'DISPATCHED' "
			+ "and t.attempt = :attempt")
	int complete(@Param("id") String id, @Param("attempt") int attempt, @Param("output") String output,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update NetworkTaskJpaEntity t set t.status = 'FAILED', t.lastError = :error, t.finishedAt = :now, "
			+ "t.claimExpiresAt = null, t.updatedAt = :now where t.id = :id and t.status = 'DISPATCHED' "
			+ "and t.attempt = :attempt")
	int fail(@Param("id") String id, @Param("attempt") int attempt, @Param("error") String error,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update NetworkTaskJpaEntity t set t.status = 'PENDING', t.assignedNodeId = null, "
			+ "t.claimExpiresAt = null, t.lastError = :error, t.nextAttemptAt = :nextAttemptAt, "
			+ "t.updatedAt = :now where t.id = :id and t.status = 'DISPATCHED' and t.attempt = :attempt")
	int requeue(@Param("id") String id, @Param("attempt") int attempt, @Param("error") String error,
			@Param("nextAttemptAt") Instant nextAttemptAt, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update NetworkTaskJpaEntity t set t.status = 'DEAD', t.lastError = :error, t.finishedAt = :now, "
			+ "t.claimExpiresAt = null, t.updatedAt = :now where t.id = :id and t.status = 'DISPATCHED' "
			+ "and t.attempt = :attempt")
	int kill(@Param("id") String id, @Param("attempt") int attempt, @Param("error") String error,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update NetworkTaskJpaEntity t set t.status = 'CANCELLED', t.finishedAt = :now, "
			+ "t.claimExpiresAt = null, t.updatedAt = :now where t.id = :id "
			+ "and t.status in ('PENDING', 'DISPATCHED')")
	int cancel(@Param("id") String id, @Param("now") Instant now);
}
