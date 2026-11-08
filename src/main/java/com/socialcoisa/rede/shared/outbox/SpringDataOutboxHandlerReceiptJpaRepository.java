package com.socialcoisa.rede.shared.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataOutboxHandlerReceiptJpaRepository
		extends JpaRepository<OutboxHandlerReceiptJpaEntity, OutboxHandlerReceiptJpaEntity.Key> {

	/**
	 * Grava o recibo uma unica vez por par (evento, handler). O INSERT condicional, e nao um save
	 * cego, e o que vale entre instancias: a segunda a chegar recebe 0 e nao repete o efeito.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(nativeQuery = true, value = "insert into processed_event_handlers (event_id, handler, processed_at) "
		+ "select :eventId, :handler, :processedAt from dual where not exists "
		+ "(select 1 from processed_event_handlers where event_id = :eventId and handler = :handler)")
	int insertIfAbsent(@Param("eventId") String eventId, @Param("handler") String handler,
			@Param("processedAt") Instant processedAt);

	/** Apaga os recibos dos eventos que a retencao esta removendo. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from OutboxHandlerReceiptJpaEntity r where r.eventId in :eventIds")
	int deleteByEventIdIn(@Param("eventIds") List<String> eventIds);
}
