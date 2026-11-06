package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Instant;

/**
 * A monitoring event after classification: everything the formatter prints, already redacted.
 *
 * @param fingerprint identity used to group repeats (same kind of failure on the same endpoint)
 * @param clientIp client address of the request that triggered it, when there is one
 * @param userId authenticated user of that request, when there is one
 * @param critical kept on disk until Telegram confirms it (ERROR, FATAL, lifecycle, health)
 */
record Alert(String eventId, Instant at, Severity severity, Category category, String title, String fingerprint,
		String endpoint, Integer status, Long durationMs, String requestId, String message, String errorClass,
		String origin, String stack, String route, String clientIp, String userId, boolean critical) {

	/** What the event is about; the label is shown on the message (pt-BR, user-facing copy). */
	enum Category {
		HTTP("Requisição HTTP"),
		PERFORMANCE("Desempenho"),
		EXCEPTION("Exceção"),
		DATABASE("Banco de dados"),
		EXTERNAL("Integração externa"),
		ASYNC("Processamento assíncrono"),
		AUTH("Autenticação e autorização"),
		AUDIT("Auditoria"),
		LIFECYCLE("Ciclo de vida"),
		HEALTH("Saúde do serviço"),
		STATE("Estado da aplicação"),
		INFRA("Infraestrutura"),
		LOG("Log");

		private final String label;

		Category(String label) {
			this.label = label;
		}

		String label() {
			return label;
		}
	}
}
