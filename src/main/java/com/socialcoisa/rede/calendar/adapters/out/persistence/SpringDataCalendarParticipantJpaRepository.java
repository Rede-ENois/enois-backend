package com.socialcoisa.rede.calendar.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataCalendarParticipantJpaRepository extends JpaRepository<CalendarParticipantJpaEntity, String> {

	Optional<CalendarParticipantJpaEntity> findByEntryIdAndUserId(String entryId, String userId);

	List<CalendarParticipantJpaEntity> findByEntryIdOrderByInvitedAtAsc(String entryId);

	/**
	 * A agenda do convidado num cruzamento só: a linha de participante vem junto com o compromisso
	 * e com a resposta, sem primeiro carregar os ids dos compromissos convidados para depois
	 * devolvê-los num `in` de tamanho variável.
	 *
	 * <p>Sem filtro de estado da entrada nem da resposta: quem chama sabe o que a leitura dele
	 * aceita. A agenda mostra o convite pendente com o selo de "convidado", e o export publica o
	 * compromisso cancelado com `STATUS:CANCELLED` no mesmo `UID` (D6). Filtrar aqui esconderia dos
	 * dois a linha que eles precisam.
	 */
	@Query("select e as entry, p.state as answer "
		+ "from CalendarParticipantJpaEntity p join CalendarEntryJpaEntity e "
		+ "on e.id = p.entryId "
		+ "where p.userId = :userId "
		+ "and (:scope is null or e.scope = :scope) "
		+ "and e.startsAt < :windowEnd and e.endsAt > :windowStart "
		+ "order by e.startsAt asc")
	List<InvitedEntryProjection> findInvitedInWindow(@Param("userId") String userId,
			@Param("scope") String scope, @Param("windowStart") Instant windowStart,
			@Param("windowEnd") Instant windowEnd);

	/**
	 * A projeção devolve a entrada e o estado bruto da coluna; a conversão para o domínio fica no
	 * adaptador, que é quem conhece os dois lados.
	 */
	interface InvitedEntryProjection {

		CalendarEntryJpaEntity getEntry();

		String getAnswer();

	}

	long countByEntryId(String entryId);

}
