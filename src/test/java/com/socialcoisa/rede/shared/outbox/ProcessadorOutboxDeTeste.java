package com.socialcoisa.rede.shared.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Aciona o worker real por evento, sem depender do agendador nem consumir a fila de outra fixture. */
@Component
public class ProcessadorOutboxDeTeste {

	private final OutboxWorker worker;
	private final JdbcTemplate jdbc;

	ProcessadorOutboxDeTeste(OutboxWorker worker, JdbcTemplate jdbc) {
		this.worker = worker;
		this.jdbc = jdbc;
	}

	public boolean processar(String tipo, String agregado) {
		String id = jdbc.queryForObject("select id from outbox_events where event_type = ? and aggregate_id = ?",
			String.class, tipo, agregado);
		return worker.processOne(id);
	}
}
