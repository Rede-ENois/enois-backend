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

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.status = :status, n.approvedBy = :actor, n.approvedAt = :now "
			+ "where n.id = :id and n.status <> 'REVOKED'")
	int approve(@Param("id") String id, @Param("status") String status, @Param("actor") String actor,
			@Param("now") Instant now);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.status = :status, n.revokedAt = :now "
			+ "where n.id = :id and n.status <> 'REVOKED'")
	int revoke(@Param("id") String id, @Param("status") String status, @Param("now") Instant now);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.status = :status where n.id = :id and n.status <> 'REVOKED'")
	int changeStatus(@Param("id") String id, @Param("status") String status);

	@Modifying
	@Query("update NetworkNodeJpaEntity n set n.runtimeVersion = :version, n.observedCpuPercent = :cpu, "
			+ "n.observedMemoryMb = :memory, n.observedRunningTasks = :tasks, n.lastSeenAt = :now where n.id = :id")
	int heartbeat(@Param("id") String id, @Param("version") String version, @Param("cpu") Double cpu,
			@Param("memory") Integer memory, @Param("tasks") Integer tasks, @Param("now") Instant now);
}
