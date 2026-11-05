package com.socialcoisa.rede.calls.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCallReportJpaRepository extends JpaRepository<CallReportJpaEntity, String> {

	/**
	 * Consulta nativa de propósito: o denunciante mora em `reports` (módulo de moderação) e o
	 * denunciado em `call_reports` (aqui). Um join JPQL entre os dois acoplaria as entidades dos
	 * dois módulos; a consulta por chave primária é barata e não cria dependência de código.
	 */
	@Query(value = "SELECT count(*) FROM call_reports c JOIN reports r ON r.id = c.report_id "
			+ "WHERE r.reporter_id = :reporterId AND c.call_id = :callId AND c.reported_user_id = :reportedUserId",
			nativeQuery = true)
	long countByReporter(@Param("reporterId") String reporterId, @Param("callId") String callId,
			@Param("reportedUserId") String reportedUserId);
}
