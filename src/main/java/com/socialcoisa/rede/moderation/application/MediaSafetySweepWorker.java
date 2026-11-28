package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.media.application.MediaPageRenderService;
import com.socialcoisa.rede.moderation.application.port.out.MediaSafetyCheckRepository;

/**
 * Rede de segurança da checagem de mídia: pega o que escapou do gancho de publicação.
 *
 * <p>O listener cobre o caminho normal, mas não todos: post publicado enquanto o pod reiniciava,
 * mídia de comentário, mensagem, ou um lote que subiu antes desta versão existir. A varredura olha
 * as imagens recentes ainda sem checagem e passa cada uma pelo mesmo serviço — o ledger garante que
 * a mesma mídia nunca é analisada duas vezes.</p>
 *
 * <p>Janela curta de propósito: conteúdo explícito precisa sair do ar em minutos, não em dias.
 * O lote é pequeno porque cada item custa um download e uma chamada de modelo.</p>
 *
 * <p><b>PDF precisa do render antes da análise:</b> a página desenhada é o que o classificador
 * vê. Por isso a passada é em duas fases — primeiro o pendente de render (e o render feito aqui
 * entrega as páginas à análise), depois a espera de estabilização já com essa fila preenchida, e só
 * então a análise. Analisar antes do render encontraria documento sem página para classificar.</p>
 */
@Component
public class MediaSafetySweepWorker {

	private static final Logger LOG = LoggerFactory.getLogger(MediaSafetySweepWorker.class);

	/** Tempo para a fila de análise ficar pronta antes da segunda fase (envio e render em curso). */
	private static final long SETTLE_MS = 5000;

	/** Rodadas de lote numa passada: o dono tem mais de um lote de mídia a rever. */
	private static final int MAX_PAGES_PER_SWEEP = 10;

	private final MediaSafetyService mediaSafety;
	private final MediaPageRenderService pageRender;
	private final MediaSafetyCheckRepository checks;
	private final Clock clock;
	private final int batch;
	private final int lookbackHours;
	private final int renderBatch;

	public MediaSafetySweepWorker(MediaSafetyService mediaSafety, MediaPageRenderService pageRender, Clock clock,
			MediaSafetyCheckRepository checks,
			@Value("${rede.moderation.media.sweep-batch:25}") int batch,
			@Value("${rede.moderation.media.lookback-hours:72}") int lookbackHours,
			@Value("${rede.media.pages.sweep-batch:10}") int renderBatch) {
		this.mediaSafety = mediaSafety;
		this.pageRender = pageRender;
		this.checks = checks;
		this.clock = clock;
		this.batch = Math.max(1, batch);
		this.lookbackHours = Math.max(1, lookbackHours);
		this.renderBatch = Math.max(1, renderBatch);
	}

	@Scheduled(initialDelayString = "${rede.moderation.media.initial-delay-ms:45000}",
			fixedDelayString = "${rede.moderation.media.interval-ms:300000}")
	public void run() {
		if (!mediaSafety.enabled()) {
			return;
		}
		try {
			sweep();
		}
		catch (RuntimeException failure) {
			LOG.error("media_safety_sweep_failed", failure);
		}
	}

	/** Uma passada; devolve quantas mídias foram barradas (também usado pelos testes). */
	public int sweep() {
		// Fase 1: desenha o que faltou. O render daqui entrega as páginas à fila de análise.
		int rendered;
		try {
			rendered = pageRender.sweep(renderBatch, Duration.ofSeconds(30));
		}
		catch (RuntimeException failure) {
			// Falha de render não impede a análise das imagens, que não dependem de página.
			LOG.error("media_safety_sweep_render_failed", failure);
			rendered = 0;
		}
		// Fase 2: espera a fila da subida (o PDF recém-enviado ainda está sendo desenhado) e analisa o
		// que a fila acumulou. A barreira pode cair aqui: a folha do documento que a subida pediu para
		// olhar leva o documento junto, e essa saída conta para a rodada.
		waitForQueue();
		int enforced = drainQueue();
		// A janela é lida depois da estabilização: a linha que a fila gravou agora não fica de fora da
		// rodada por ter sido escrita depois de a rodada começar.
		Instant since = clock.instant().minus(Duration.ofHours(lookbackHours));

		// Fase 3, pelo sistema e pelo dono. Pelo sistema: o que está sem veredito (lista contínua) e o
		// que já gravou violação na janela. Pelo dono: o documento de quem publicou ou já tem violação
		// no ledger — o takedown do portfólio não espera o lote alcançar a média do sistema.
		// Barrado na fase 3 conta para a rodada: a barreira tirou a peça do ar, e a peça fora do ar
		// não volta à fila — sem esta conta a varredura diria que nada foi barrado.
		int checked = 0;
		Set<String> seen = new HashSet<>();
		for (int round = 0; round < MAX_PAGES_PER_SWEEP; round++) {
			List<String> pendentes = new ArrayList<>(mediaSafety.pending(since, batch));
			if (round == 0) {
				pendentes.addAll(checks.findViolatorsSince(since, batch));
			}
			pendentes.removeAll(seen);
			if (pendentes.isEmpty()) {
				break;
			}
			for (String mediaId : pendentes) {
				MediaSafetyService.Outcome outcome = mediaSafety.check(mediaId);
				checked++;
				seen.add(mediaId);
				if ("ENFORCED".equals(outcome.status())) {
					enforced++;
				}
			}
		}
		if (mediaSafety.enabled() && (checked > 0 || rendered > 0)) {
			LOG.info("media_safety_sweep done checked={} enforced={} rendered={}", checked, enforced, rendered);
		}
		return enforced;
	}

	/**
	 * Esvazia a fila de análise e devolve quantas mídias foram barradas. Uma mídia que falha não
	 * impede as outras: a rodada seguinte tenta de novo.
	 */
	private int drainQueue() {
		try {
			return mediaSafety.analyseQueued();
		}
		catch (RuntimeException failure) {
			LOG.error("media_safety_sweep_queue_failed", failure);
			return 0;
		}
	}

	/** Deixa a tarefa de render da subida terminar e entregar as páginas à fila de análise. */
	private static void waitForQueue() {
		try {
			Thread.sleep(SETTLE_MS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
