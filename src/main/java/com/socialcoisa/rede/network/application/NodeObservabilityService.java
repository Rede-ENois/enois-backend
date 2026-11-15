package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository.LogLine;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository.Sample;
import com.socialcoisa.rede.network.domain.NetworkNode;

/**
 * O painel de observabilidade de UM nó: a série recente e o console recente, para a página do nó
 * mostrar os mesmos quadros do topo do painel de monitoração — dentro do nó.
 *
 * <p><b>De onde vem o dado.</b> O runtime do nó não abre porta (brief §17: o Control Plane fala com
 * o nó, e o nó nunca é endereço de entrada). Se o painel "lesse direto do nó", o backend teria de
 * abrir conexão para dentro da máquina de um participante — exatamente o que o modelo pull impede.
 * Então o nó <b>empurra</b> a amostra curta no heartbeat que ele já faz, e o Control Plane guarda uma
 * janela pequena para a tela. O efeito visível é o mesmo (quadro ao vivo, atualizado a cada
 * heartbeat); o caminho é o que a arquitetura permite.</p>
 *
 * <p><b>O que isto não é.</b> Não é retenção de log nem auditoria: 60 pontos e 200 linhas por nó,
 * apagados por teto na escrita. Auditoria é outra tabela, com outra promessa.</p>
 */
@Service
public class NodeObservabilityService {

	/** Pontos do gráfico por nó: um por minuto, uma hora de janela. */
	static final int SAMPLE_KEEP = 60;

	/** Linhas do console por nó. O teto é pequeno de propósito: é tela, não arquivo. */
	static final int LOG_KEEP = 200;

	/** Tamanho da mensagem de log — o console mostra uma linha, não um stack trace. */
	private static final int MAX_LOG = 500;

	private final NodeObservabilityRepository repository;
	private final Clock clock;
	private final java.util.function.Supplier<String> ids = () -> NodeIds.next("nl");

	NodeObservabilityService(NodeObservabilityRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/**
	 * A amostra que o nó mandou no heartbeat. O ponto é do <b>minuto</b> corrente: dois heartbeats no
	 * mesmo minuto trocam o valor em vez de abrir dois pontos, e a série fica com passo regular.
	 *
	 * <p>Valores ausentes continuam ausentes: um nó que não reportou CPU não vira "0% usado" no
	 * gráfico — nulo e zero são coisas diferentes, como no disco livre.</p>
	 */
	@Transactional
	public void record(NetworkNode node, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks) {
		Instant now = clock.instant();
		Instant minute = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
		repository.saveSample(new Sample(node.id(), minute, cpuPercent, memoryMb, diskFreeMb, runningTasks, null,
			node.status().name(), null));
		repository.trimSamples(node.id(), SAMPLE_KEEP);
	}

	/** Uma linha do console do nó, enviada junto com o heartbeat. */
	@Transactional
	public void log(NetworkNode node, String level, String message, Instant loggedAt) {
		if (message == null || message.isBlank()) {
			return;
		}
		String normalized = level == null || level.isBlank() ? "INFO" : level.strip().toUpperCase();
		if (!normalized.matches("DEBUG|INFO|WARN|ERROR")) {
			normalized = "INFO";
		}
		String trimmed = message.strip();
		if (trimmed.length() > MAX_LOG) {
			trimmed = trimmed.substring(0, MAX_LOG);
		}
		repository.appendLog(new LogLine(ids.get(), node.id(),
			loggedAt == null ? clock.instant() : loggedAt, normalized, trimmed));
		repository.trimLogs(node.id(), LOG_KEEP);
	}

	/** O quadro do nó: série (antiga → nova) e console (nova → antiga). */
	@Transactional(readOnly = true)
	public Panel panel(String nodeId, int sampleLimit, int logLimit) {
		List<Sample> samples = repository.samples(nodeId, clamp(sampleLimit, SAMPLE_KEEP));
		List<LogLine> logs = repository.logs(nodeId, clamp(logLimit, LOG_KEEP));
		return new Panel(nodeId, clock.instant(), List.copyOf(samples), logs.stream().map(View::of).toList(),
			summarize(samples), logLevels(logs));
	}

	/**
	 * Agregados do quadro — os mesmos números do topo do painel de monitoração, calculados sobre a
	 * janela do nó. Média só sobre o que foi <b>medido</b>: um nó com metade dos heartbeats sem
	 * disco não pode ter a média puxada para baixo por zeros que ninguém mediu.
	 */
	record Summary(Double cpuAvgPercent, Double cpuMaxPercent, Integer memoryMbLatest, Long diskFreeMbLatest,
			Integer runningTasksLatest, int sampleCount) {
	}

	record View(String id, String at, String level, String message) {
		static View of(LogLine line) {
			return new View(line.id(), line.loggedAt().toString(), line.level(), line.message());
		}
	}

	/** O que a página do nó desenha. {@code generatedAt} deixa claro que o quadro é do instante. */
	public record Panel(String nodeId, Instant generatedAt, List<Sample> series, List<View> console,
			Summary summary, java.util.Map<String, Long> consoleLevels) {
	}

	private static Summary summarize(List<Sample> samples) {
		List<Double> cpus = samples.stream().map(Sample::cpuPercent).filter(java.util.Objects::nonNull).toList();
		Double avg = cpus.isEmpty() ? null : cpus.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		Double max = cpus.isEmpty() ? null : cpus.stream().mapToDouble(Double::doubleValue).max().orElse(0);
		Sample latest = samples.isEmpty() ? null : samples.get(samples.size() - 1);
		return new Summary(avg == null ? null : Math.round(avg * 10) / 10.0,
			max == null ? null : Math.round(max * 10) / 10.0, latest == null ? null : latest.memoryMb(),
			latest == null ? null : latest.diskFreeMb(), latest == null ? null : latest.runningTasks(),
			samples.size());
	}

	private static java.util.Map<String, Long> logLevels(List<LogLine> logs) {
		java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
		for (LogLine line : logs) {
			counts.merge(line.level(), 1L, Long::sum);
		}
		return counts;
	}

	private static int clamp(int requested, int max) {
		return Math.min(Math.max(requested, 1), max);
	}
}