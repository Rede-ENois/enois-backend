package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
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
	 * Mesma janela meia-aberta, mas por conjunto de ids: são os compromissos que outra pessoa
	 * organizou e convidou quem está lendo. Lista vazia nunca chega aqui (o adaptador devolve nada
	 * antes), porque `in ()` não é SQL válido em toda versão.
	 */
	@Query("select e from CalendarEntryJpaEntity e where e.id in :ids "
		+ "and (:scope is null or e.scope = :scope) "
		+ "and e.startsAt < :windowEnd and e.endsAt > :windowStart "
		+ "order by e.startsAt asc")
	List<CalendarEntryJpaEntity> findInWindowByIds(@Param("ids") Collection<String> ids,
			@Param("scope") String scope, @Param("windowStart") Instant windowStart,
			@Param("windowEnd") Instant windowEnd);

	Optional<CalendarEntryJpaEntity> findByOwnerIdAndSourceTypeAndSourceId(String ownerId, String sourceType,
			String sourceId);

	void deleteByOwnerIdAndSourceTypeAndSourceId(String ownerId, String sourceType, String sourceId);

}
