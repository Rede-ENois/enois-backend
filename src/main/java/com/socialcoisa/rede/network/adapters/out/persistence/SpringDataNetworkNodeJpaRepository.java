package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataNetworkNodeJpaRepository extends JpaRepository<NetworkNodeJpaEntity, String> {

	@Query("select n from NetworkNodeJpaEntity n order by n.createdAt desc")
	List<NetworkNodeJpaEntity> listRecent(PageRequest page);

	long countByStatus(String status);

	/*
	 * Toda transição administrativa soma 1 em `configVersion` e carimba `configIssuedAt`. A versão é
	 * derivada do ciclo de vida dentro do próprio UPDATE: um contador que o chamador incrementasse
	 * dependeria de ele lembrar, e a primeira transição esquecida deixaria o nó aplicando permissão
	 * velha para sempre.
	 */

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.status = :status, n.approvedBy = :actor, n.approvedAt = :now, "
			+ "n.configVersion = n.configVersion + 1, n.configIssuedAt = :now "
			+ "where n.id = :id and n.status <> 'REVOKED'")
	int approve(@Param("id") String id, @Param("status") String status, @Param("actor") String actor,
			@Param("now") Instant now);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.status = :status, n.revokedAt = :now, "
			+ "n.configVersion = n.configVersion + 1, n.configIssuedAt = :now "
			+ "where n.id = :id and n.status <> 'REVOKED'")
	int revoke(@Param("id") String id, @Param("status") String status, @Param("now") Instant now);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.status = :status, n.configVersion = n.configVersion + 1, "
			+ "n.configIssuedAt = :now where n.id = :id and n.status <> 'REVOKED'")
	int changeStatus(@Param("id") String id, @Param("status") String status, @Param("now") Instant now);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.runtimeVersion = :version, n.observedCpuPercent = :cpu, "
			+ "n.observedMemoryMb = :memory, n.observedRunningTasks = :tasks, n.lastSeenAt = :now where n.id = :id")
	int heartbeat(@Param("id") String id, @Param("version") String version, @Param("cpu") Double cpu,
			@Param("memory") Integer memory, @Param("tasks") Integer tasks, @Param("now") Instant now);

	@Query("select n from NetworkNodeJpaEntity n where n.status = 'ACTIVE' and n.lastSeenAt >= :seenAfter "
			+ "order by n.lastSeenAt desc")
	List<NetworkNodeJpaEntity> findCandidates(@Param("seenAfter") Instant seenAfter, PageRequest page);

	/*
	 * Desfecho de tarefa, em uma instrução só. Concorrência: dois resultados do mesmo nó chegam por
	 * requisições distintas e o streak é lido-modificado-escrito. Um UPDATE relativo (o próprio banco
	 * calcula o próximo valor) evita a leitura seguida de escrita, que perderia uma das duas.
	 *
	 * clearAutomatically: o chamador relê o nó logo depois para decidir a janela de remoção; sem
	 * limpar, a releitura viria do contexto de persistência e traria o streak ANTERIOR, então a
	 * decisão usaria um número que já não existe.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update NetworkNodeJpaEntity n set n.consecutiveFailures = case when :success = true then 0 "
			+ "else n.consecutiveFailures + 1 end, "
			+ "n.observedLatencyMs = case when :latency is null then n.observedLatencyMs "
			+ "when n.observedLatencyMs is null then :latency "
			+ "else (n.observedLatencyMs * 3 + :latency) / 4 end "
			+ "where n.id = :id")
	int recordOutcome(@Param("id") String id, @Param("success") boolean success, @Param("latency") Long latency);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.quarantinedUntil = :until, n.quarantineReason = :reason "
			+ "where n.id = :id")
	int quarantine(@Param("id") String id, @Param("reason") String reason, @Param("until") Instant until);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.quarantinedUntil = null, n.quarantineReason = null, "
			+ "n.consecutiveFailures = 0 where n.id = :id")
	int release(@Param("id") String id);

	@Query("select n from NetworkNodeJpaEntity n where n.status = 'ACTIVE' and n.quarantinedUntil is not null "
			+ "and n.quarantinedUntil <= :now order by n.quarantinedUntil asc")
	List<NetworkNodeJpaEntity> findQuarantineExpired(@Param("now") Instant now, PageRequest page);
}
