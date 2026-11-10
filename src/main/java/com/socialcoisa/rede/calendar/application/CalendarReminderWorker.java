package com.socialcoisa.rede.calendar.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A cada 30 s olha o relógio: o lembrete que venceu é entregue pelo serviço (decisão D4).
 *
 * <p>Mesmo desenho do {@code ScheduledPostPublishWorker}: o worker não sabe o que é lembrete, só
 * quando perguntar. Uma falha é registrada e o laço continua — é o próximo ciclo que tenta de novo,
 * não uma exceção que derruba o agendador.
 */
@Component
class CalendarReminderWorker {

	private static final Logger LOG = LoggerFactory.getLogger(CalendarReminderWorker.class);

	private final CalendarReminderService service;

	CalendarReminderWorker(CalendarReminderService service) {
		this.service = service;
	}

	@Scheduled(fixedDelayString = "${rede.calendar.reminder-delay-ms:30000}")
	void deliverDue() {
		try {
			int sent = service.deliverDue();
			if (sent > 0) {
				LOG.info("Lembretes de compromisso enviados: {}", sent);
			}
		}
		catch (RuntimeException error) {
			LOG.warn("Falha no envio de lembretes de compromisso: {}", error.getMessage());
		}
	}

}