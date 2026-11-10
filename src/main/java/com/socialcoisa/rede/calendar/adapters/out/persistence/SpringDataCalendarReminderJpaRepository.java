package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCalendarReminderJpaRepository extends JpaRepository<CalendarReminderJpaEntity, String> {

	/**
	 * Vencidos e ainda não enviados, do mais antigo ao mais novo. `sent_at is null` não entra no
	 * índice de propósito (a coluna nula no meio quebraria o range), e o volume por ciclo é pequeno:
	 * o worker pede um punhado de cada vez.
	 */
	@Query("select r from CalendarReminderJpaEntity r "
		+ "where r.sentAt is null and r.attempts < :maxAttempts and r.dueAt <= :now "
		+ "order by r.dueAt asc")
	List<CalendarReminderJpaEntity> findDue(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
			org.springframework.data.domain.Pageable limit);

	List<CalendarReminderJpaEntity> findByEntryId(String entryId);

	/**
	 * A chave única da tabela: um lembrete por pessoa e antecedência em cada compromisso. Escrita com
	 * `@Query` porque o nome derivado é ambíguo para o Spring Data ("MinutesBefore" vira "minutes" +
	 * "Before") e a consulta não sobe.
	 */
	@Query("select r from CalendarReminderJpaEntity r where r.entryId = :entryId and r.userId = :userId "
		+ "and r.minutesBefore = :minutesBefore")
	Optional<CalendarReminderJpaEntity> findOne(@Param("entryId") String entryId, @Param("userId") String userId,
			@Param("minutesBefore") int minutesBefore);

	/**
	 * Encerra de uma vez os lembretes que ainda não saíram: cancelar ou reagendar o compromisso não
	 * pode deixar um aviso na fila para o horário antigo.
	 */
	@Query("select r from CalendarReminderJpaEntity r where r.entryId in :entryIds and r.sentAt is null")
	List<CalendarReminderJpaEntity> findPendingByEntryIds(@Param("entryIds") Collection<String> entryIds);

}