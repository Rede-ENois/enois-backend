package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.NodeUptimeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeAvailability;

/**
 * Disponibilidade histórica do nó (brief §7, V369): cada heartbeat acrescenta ao dia o trecho que
 * decorreu desde o contato anterior, e a leitura soma os dias medidos.
 *
 * <h2>A regra do trecho</h2>
 *
 * <p>Entre dois heartbeats consecutivos o Control Plane sabe uma coisa só: o nó estava vivo no
 * primeiro e vivo no segundo. O trecho conta como observado inteiro, e como <b>no ar</b> até
 * {@code staleAfter} depois do primeiro contato — depois disso a janela de "vivo" venceu, e o resto do
 * trecho é tempo fora. É a mesma definição de "vivo" do painel e da tela do participante
 * ({@link NetworkNode#alive}), para não haver dois números sobre a mesma coisa.</p>
 *
 * <h2>Em que dia o trecho cai</h2>
 *
 * <p>O trecho vai inteiro para o dia do <b>último</b> contato — o dia em que houve a medida. Um trecho
 * que atravessa a meia-noite não é partido: partir exigiria saber se o nó estava no ar às 00:00, e o
 * que se mediu foram dois contatos. A consequência, que a tela declara: uma ausência de três dias
 * cai toda no dia da volta, e é o que faz o número ser <b>conservador</b> — a queda não some da janela
 * de 7 dias por ter caído na virada.</p>
 *
 * <h2>O que a soma não faz</h2>
 *
 * <p>Só conta quando o contato avançou: dois heartbeats no mesmo instante somariam o mesmo trecho duas
 * vezes.</p>
 */
@Service
public class NodeUptimeService {

	/** Quantos dias a leitura guarda no máximo, e o padrão da janela. */
	public static final int DEFAULT_WINDOW_DAYS = 7;
	public static final int MAX_WINDOW_DAYS = 90;

	private static final Logger LOG = LoggerFactory.getLogger(NodeUptimeService.class);

	private final NodeUptimeRepository uptime;
	private final Clock clock;
	private final Duration staleAfter;
	private final int retentionDays;

	public NodeUptimeService(NodeUptimeRepository uptime, Clock clock,
			// A MESMA janela de "vivo" do painel, do scheduler e da reconexão
			// (`rede.network.node-stale-seconds`), para não haver dois números sobre a mesma coisa.
			@Value("${rede.network.node-stale-seconds:180}") long staleSeconds,
			@Value("${rede.network.uptime.retention-days:365}") int retentionDays) {
		this.uptime = uptime;
		this.clock = clock;
		this.staleAfter = Duration.ofSeconds(Math.max(30, staleSeconds));
		this.retentionDays = Math.max(1, retentionDays);
	}

	/**
	 * Conta o trecho entre o contato anterior e este heartbeat.
	 *
	 * <p>Não conta nada quando não há anterior (primeiro contato: o nó acabou de aparecer, e o tempo
	 * antes disso não existia para a rede) nem quando o relógio não avançou. E não conta quando o
	 * trecho já foi contado: o mesmo heartbeat publicado de novo (retry, leitura de config repetida)
	 * chega com o mesmo {@code now}, e somar de novo diria que ele esteve no ar mais tempo do que é
	 * possível.</p>
	 *
	 * @return {@code true} quando o trecho entrou no dia
	 */
	public boolean record(NetworkNode node, Instant previousSeenAt, Instant now) {
		if (node == null || previousSeenAt == null || now == null || !now.isAfter(previousSeenAt)) {
			return false;
		}
		long observado = Duration.between(previousSeenAt, now).getSeconds();
		if (observado <= 0) {
			return false;
		}
		LocalDate dia = now.atZone(ZoneOffset.UTC).toLocalDate();
		Instant jaContado = uptime.lastContact(node.id(), dia);
		if (jaContado != null && !now.isBefore(jaContado)) {
			// O último contato do dia já é este ou é posterior: o trecho que termina aqui foi, na
			// melhor das hipóteses, o mesmo já somado. Contato ANTERIOR ao já gravado (chegada
			// atrasada) é trecho que ainda não foi contado e soma — o dia acumula o que houve, não
			// só o que chegou em ordem.
			return false;
		}
		return uptime.add(node.id(), dia, presentBetween(previousSeenAt, now), observado, previousSeenAt, now);
	}

	/**
	 * Quanto do trecho {@code [de, ate]} está dentro da janela de "vivo" contada a partir de {@code de}.
	 * Visível para a prova do limite: o trecho exatamente igual à janela é "no ar"; um segundo a mais
	 * já conta como fora.
	 */
	long presentBetween(Instant de, Instant ate) {
		Instant vence = de.plus(staleAfter);
		Instant fimNoAr = vence.isBefore(ate) ? vence : ate;
		return Math.max(0, Duration.between(de, fimNoAr).getSeconds());
	}

	/** A disponibilidade dos últimos {@code days} dias (padrão sete), até hoje. */
	public NodeAvailability last(NetworkNode node, int days) {
		int janela = Math.min(Math.max(days, 1), MAX_WINDOW_DAYS);
		LocalDate hoje = today();
		LocalDate desde = hoje.minusDays(janela - 1L);
		List<NodeAvailability.Day> naJanela = uptime.days(node.id(), hoje).stream()
			.filter(d -> !d.date().isBefore(desde)).toList();
		// A conta (soma, proporção ponderada, tempo fora) fica numa fábrica do domínio: é uma regra
		// só, e a tela do admin e a do participante não podem dividir de jeitos diferentes.
		return NodeAvailability.of(naJanela);
	}

	/** O dia de hoje, do jeito que o heartbeat o está contando — o painel mostra o parcial. */
	public LocalDate today() {
		return clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
	}

	/** Apaga o que passou da retenção. Devolve quantas linhas saíram. */
	public int purge() {
		LocalDate limite = today().minusDays(retentionDays - 1L);
		return uptime.purgeBefore(limite);
	}

	/**
	 * Varredura diária da retenção.
	 *
	 * <p>Um ano de linhas por nó é barato, mas cresce para sempre se ninguém apagar, e a rota lê no
	 * máximo 90 dias — o resto seria dado que ninguém consulta. A varredura é por índice ({@code dia})
	 * e roda fora do horário de pico, como a das métricas do Storage.</p>
	 */
	@Component
	static class RetentionWorker {

		private final NodeUptimeService service;
		private final boolean enabled;

		RetentionWorker(NodeUptimeService service,
				@Value("${rede.network.uptime.retention.enabled:true}") boolean enabled) {
			this.service = service;
			this.enabled = enabled;
		}

		@Scheduled(cron = "${rede.network.uptime.retention.cron:0 20 4 * * *}")
		public void run() {
			if (!enabled) {
				return;
			}
			int removidas = service.purge();
			LOG.debug("node_uptime: retencao removeu {} linha(s) de dia", removidas);
		}
	}
}