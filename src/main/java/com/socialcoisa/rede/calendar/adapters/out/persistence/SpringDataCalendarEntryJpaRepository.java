package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCalendarEntryJpaRepository extends JpaRepository<CalendarEntryJpaEntity, String> {

	/**
	 * Janela meia-aberta: o compromisso precisa começar antes do fim da janela E terminar depois do
	 * início dela. Comparação estrita dos dois lados, senão um compromisso que termina às 10h
	 * apareceria na janela que começa às 10h.
	 */
	@Query("select e from CalendarEntryJpaEntity e where e.ownerId = :ownerId "
		+ "and (:scope is null or e.scope = :scope) "
		+ "and e.startsAt < :windowEnd and e.endsAt > :windowStart "
		+ "order by e.startsAt asc")
	List<CalendarEntryJpaEntity> findInWindow(@Param("ownerId") String ownerId, @Param("scope") String scope,
			@Param("windowStart") Instant windowStart, @Param("windowEnd") Instant windowEnd);

	/**
	 * Os compromissos com convite ainda sem resposta, direto na tabela de participantes: o banco
	 * cruza convite com compromisso numa consulta só, sem primeiro carregar a lista de convites
	 * pendentes para então mandar os ids de volta num `in`. Ativo e `INVITED` filtrados aqui: a
	 * resposta (ou o cancelamento) tira o convite da fila, e a ordem segue a idade do convite.
	 */
	@Query("select e from CalendarEntryJpaEntity e join CalendarParticipantJpaEntity p "
		+ "on p.entryId = e.id and p.userId = :userId and p.state = 'INVITED' "
		+ "where e.state = 'ACTIVE' "
		+ "order by p.invitedAt asc")
	List<CalendarEntryJpaEntity> findPendingInvitations(@Param("userId") String userId);

	/** Todas as irmãs de uma série, do mais antigo ao mais novo — histórico incluído. */
	@Query("select e from CalendarEntryJpaEntity e where e.seriesId = :seriesId order by e.startsAt asc")
	List<CalendarEntryJpaEntity> findBySeriesIdOrderByStartsAtAsc(@Param("seriesId") String seriesId);

	Optional<CalendarEntryJpaEntity> findBySeriesIdAndStartsAt(String seriesId, Instant startsAt);

	/**
	 * As pontas de série, de qualquer situação: cancelar uma ocorrência não encerra a série, e a
	 * ponta cancelada continua orientando a extensão até o dono encerrar por `stop`.
	 */
	List<CalendarEntryJpaEntity> findByTailTrue();

	Optional<CalendarEntryJpaEntity> findByOwnerIdAndSourceTypeAndSourceId(String ownerId, String sourceType,
			String sourceId);

	void deleteByOwnerIdAndSourceTypeAndSourceId(String ownerId, String sourceType, String sourceId);

}
