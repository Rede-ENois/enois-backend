package com.socialcoisa.rede.wrapped.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.wrapped.domain.WrappedAno;

/**
 * O agendador da retrospectiva. Fora da janela das madrugadas (00:00 às 05:00, no fuso da
 * aplicação), entre 26/12 e 01/01 (a janela de exibição), o worker não inicia NENHUMA conta — o
 * processamento pesado nunca disputa o horário normal da plataforma.
 *
 * <p>Dentro da janela: uma conta por vez (o serviço pega a próxima da fila e reserva com claim
 * atômico), do começo ao fim, com a gravação atômica. Às 05:00 o passo para de iniciar conta nova;
 * a que estiver em andamento termina antes — o encerramento é controlado, nunca corta a metade. Se
 * a quantidade não couber numa madrugada, a fila segue nas seguintes; contas diferentes podem
 * aparecer em datas diferentes (o briefing aceita isso).</p>
 *
 * <p>A fila é construída na virada do ano (a primeira janela, 26/12 00:00). Em teste, certas
 * chaves forçam a janela e o ano para que os passos sejam verificáveis com um {@link Clock} fixo.</p>
 */
@Component
class WrappedWorker {

	private static final Logger LOG = LoggerFactory.getLogger(WrappedWorker.class);
	static final LocalTime ABRE = LocalTime.MIDNIGHT;
	static final LocalTime FECHA = LocalTime.of(5, 0);
	/** A primeira janela constrói a fila do ano que acabou. */
	private static final LocalDate DIA_DA_CONSTRUCAO = LocalDate.of(2026, 12, 26);
	/** Contas por passo: o próximo delay continua; nenhum passo dorme horas. */
	private static final int LIMITE_DO_PASSO = 25;

	private final WrappedService service;
	private final WrappedFilaConstrutor construtor;
	private final Clock clock;
	private final int ano;
	private final boolean habilitado;
	private final boolean forcarJanela;

	WrappedWorker(WrappedService service, WrappedFilaConstrutor construtor, Clock clock,
			@Value("${rede.wrapped.ano:2026}") int ano,
			@Value("${rede.wrapped.habilitado:true}") boolean habilitado,
			@Value("${rede.wrapped.forcar-janela:false}") boolean forcarJanela) {
		this.service = service;
		this.construtor = construtor;
		this.clock = clock;
		this.ano = ano;
		this.habilitado = habilitado;
		this.forcarJanela = forcarJanela;
	}

	@Scheduled(fixedDelayString = "${rede.wrapped.delay-ms:60000}")
	void passo() {
		try {
			if (!habilitado) {
				return;
			}
			Instant agora = clock.instant();
			if (!forcarJanela && !(dentroDoPeriodoDeExibicao(agora) && dentroDaJanelaDeProcessamento(agora))) {
				return;
			}
			service.recuperarReservas(ano);
			if (deveConstruirFila()) {
				construtor.construir(ano, agora);
				return;
			}
			processarLote(agora);
		}
		catch (RuntimeException erro) {
			LOG.warn("Falha no passo do Énóis Wrapped: {}", erro.getMessage());
		}
	}

	int processarLote(Instant agora) {
		int processadas = 0;
		while (processadas < LIMITE_DO_PASSO && cortePermite(agora)) {
			if (!service.processarProxima(ano)) {
				break;
			}
			processadas++;
		}
		if (processadas > 0) {
			LOG.info("Énóis Wrapped {}: {} contas processadas neste passo", ano, processadas);
		}
		return processadas;
	}

	private boolean cortePermite(Instant agora) {
		return forcarJanela || dentroDaJanelaDeProcessamento(agora);
	}

	private boolean deveConstruirFila() {
		return LocalDate.ofInstant(clock.instant(), WrappedAno.FUSO).isEqual(DIA_DA_CONSTRUCAO);
	}

	/** Dentro de 26/12 00:00 até 01/01 00:00 (fuso da aplicação). */
	boolean dentroDoPeriodoDeExibicao(Instant agora) {
		return WrappedAno.visivelEm(ano, agora);
	}

	/** A janela do briefing: 00:00 até 05:00 (exclusivo), no fuso da aplicação. */
	boolean dentroDaJanelaDeProcessamento(Instant agora) {
		LocalTime hora = LocalTime.ofInstant(agora, WrappedAno.FUSO);
		return !hora.isBefore(ABRE) && hora.isBefore(FECHA);
	}
}
