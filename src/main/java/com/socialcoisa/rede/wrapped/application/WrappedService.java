package com.socialcoisa.rede.wrapped.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedAssuntos;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedComunidadesRepository;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedContasRepository;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedFilaRepository;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedHistoricoRepository;
import com.socialcoisa.rede.wrapped.domain.WrappedAno;
import com.socialcoisa.rede.wrapped.domain.WrappedRetrospectiva;

import tools.jackson.databind.ObjectMapper;

/**
 * A orquestração da retrospectiva: gera a de UMA conta (reservada) e lê a da própria sessão.
 *
 * <p>A geração é atômica por conta: reserva, calcula com o agregador, grava o resultado e conclui
 * numa transação. Falhou no meio? Nada fica gravado e a conta é marcada como falha (a reserva
 * expirada a devolveria à fila, mas a regra do projeto é não engolir erro: a falha fica registrada).</p>
 */
@Service
public class WrappedService {

	private static final Logger LOG = LoggerFactory.getLogger(WrappedService.class);
	/** Quanto tempo uma reserva vale antes de outra instância poder pegar a conta. */
	static final Duration RESERVA = Duration.ofMinutes(20);

	private final WrappedFilaRepository fila;
	private final WrappedContasRepository contas;
	private final WrappedHistoricoRepository historico;
	private final WrappedComunidadesRepository comunidades;
	private final WrappedAssuntos assuntos;
	private final ObjectMapper json;
	private final Clock clock;

	WrappedService(WrappedFilaRepository fila, WrappedContasRepository contas,
			WrappedHistoricoRepository historico, WrappedComunidadesRepository comunidades,
			WrappedAssuntos assuntos, ObjectMapper json, Clock clock) {
		this.fila = fila;
		this.contas = contas;
		this.historico = historico;
		this.comunidades = comunidades;
		this.assuntos = assuntos;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Processa a próxima conta da fila. Devolve {@code true} quando processou uma conta (pronta ou
	 * falha), {@code false} quando não havia nada a fazer. Nunca joga exceção para o agendador.
	 */
	public boolean processarProxima(int ano) {
		List<String> proximas = fila.proximas(ano, 1);
		if (proximas.isEmpty()) {
			return false;
		}
		String userId = proximas.get(0);
		Instant agora = clock.instant();
		if (!fila.reservar(userId, ano, agora, agora.plus(RESERVA))) {
			// Outra instância pegou a conta entre a leitura e a reserva: tenta na próxima.
			return true;
		}
		try {
			WrappedRetrospectiva retrospectiva = gerar(userId, ano);
			String resultado = json.writeValueAsString(retrospectiva);
			fila.concluir(userId, ano, resultado, retrospectiva.dadosInsuficientes(), clock.instant());
			return true;
		}
		catch (RuntimeException erro) {
			LOG.warn("Falha ao gerar a retrospectiva {} de {}: {}", ano, userId, erro.getMessage());
			fila.falhar(userId, ano, mensagem(erro), clock.instant());
			return true;
		}
	}

	/** Um passo de retomada: devolve à fila quem ficou PROCESSING com a reserva vencida. */
	public int recuperarReservas(int ano) {
		return fila.expirarReservas(ano, clock.instant());
	}

	/** A retrospectiva concluída de uma conta, para a leitura da tela (sem consultar o histórico). */
	@Transactional(readOnly = true)
	public java.util.Optional<WrappedRetrospectiva> retrospectiva(String userId, int ano) {
		return fila.buscar(userId, ano)
				.filter(r -> r.estado() == com.socialcoisa.rede.wrapped.domain.WrappedEstado.DONE
						&& r.resultadoJson() != null)
				.map(r -> json.readValue(r.resultadoJson(), WrappedRetrospectiva.class));
	}

	/** A conta tem linha na fila no ano (em qualquer estado)? */
	public boolean elegivel(String userId, int ano) {
		return fila.buscar(userId, ano).isPresent();
	}

	/** Gera a retrospectiva de uma conta (sem a reserva — usado pelo worker já reservado e pelo teste). */
	public WrappedRetrospectiva gerar(String userId, int ano) {
		WrappedAno w = WrappedAno.de(ano);
		WrappedLeitura leitura = (u, de, ate, cursor, limite) -> historico.pagina(u, de, ate, cursor, limite);
		WrappedRetrospectiva base = WrappedAgregador.montar(userId, w, leitura, comunidades, assuntos);
		return WrappedAgregador.comCredibilidade(base, contas.credibilidade(userId));
	}

	private static String mensagem(RuntimeException erro) {
		String m = erro.getMessage();
		return m == null ? erro.getClass().getSimpleName() : m.length() > 1900 ? m.substring(0, 1900) : m;
	}
}
