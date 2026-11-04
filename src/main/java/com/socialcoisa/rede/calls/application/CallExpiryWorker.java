package com.socialcoisa.rede.calls.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.calls.application.port.out.CallRepository;

/**
 * Fecha o que ninguém fechou: 1:1 que ninguém atendeu, grupo que ficou vazio e sessão sem sinal de
 * vida. Sem isso, uma chamada nunca atendida ficaria "tocando" para sempre na tela de alguém.
 *
 * <p>Cada chamada é fechada na própria transação, e a transição é condicional: se o gerenciador de
 * chamada manda antes (atendeu, cancelou, encerrou), o worker perde a corrida e não faz nada.
 */
@Component
public class CallExpiryWorker {

	private static final Logger LOG = LoggerFactory.getLogger(CallExpiryWorker.class);
	private static final int BATCH = 100;

	private final CallRepository calls;
	private final CallService service;
	private final Clock clock;

	CallExpiryWorker(CallRepository calls, CallService service, Clock clock) {
		this.calls = calls;
		this.service = service;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
	void sweep() {
		Instant now = clock.instant();
		// O prazo das abertas já embute o abandono: o domínio decide o estado final pelo estado atual.
		int closed = service.expire(calls.findExpired(now, BATCH), now);
		if (closed > 0) {
			LOG.info("Chamadas expiradas fechadas: {}", closed);
		}
	}

	/** O mesmo caminho do agendador, chamável sob demanda (testes e suporte). */
	public int sweepOnce() {
		Instant now = clock.instant();
		return service.expire(calls.findExpired(now, BATCH), now);
	}
}
