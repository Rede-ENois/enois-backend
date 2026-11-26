package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository.LogLine;
import com.socialcoisa.rede.network.application.port.out.NodeObservabilityRepository.Sample;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeHealth;

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

	/** Mesma janela de "vivo" do scheduler, do painel e da cobertura: 180 s. */
	static final long ALIVE_SECONDS = 180;

	/**
	 * Janela do histograma de latência (brief §8.2). A série de amostras é do minuto; a latência é
	 * lida em horas porque um p99 sobre 60 amostras é o próprio máximo, e a cauda só tem sentido com
	 * volume. Vinte e quatro horas é a janela que o painel mostra.
	 */
	static final int LATENCY_WINDOW_HOURS = 24;

	private final NodeObservabilityRepository repository;
	private final NetworkNodeRepository nodes;
	private final ContentVersionRepository versions;
	private final Clock clock;
	private final java.util.function.Supplier<String> ids = () -> NodeIds.next("nl");

	NodeObservabilityService(NodeObservabilityRepository repository, NetworkNodeRepository nodes,
			ContentVersionRepository versions, Clock clock) {
		this.repository = repository;
		this.nodes = nodes;
		this.versions = versions;
		this.clock = clock;
	}

	/**
	 * A amostra que o nó mandou no heartbeat. O ponto é do <b>minuto</b> corrente: dois heartbeats no
	 * mesmo minuto trocam o valor em vez de abrir dois pontos, e a série fica com passo regular.
	 *
	 * <p>Valores ausentes continuam ausentes: um nó que não reportou CPU não vira "0% usado" no
	 * gráfico — nulo e zero são coisas diferentes, como no disco livre.</p>
	 *
	 * <p>{@code memoryMb} é o <b>uso</b>, já derivado pelo serviço do nó (runtime antigo manda o
	 * disponível em {@code memoryMb}, e a amostra sem a derivação registraria a máquina ociosa com
	 * "7,9 GB em uso").</p>
	 */
	@Transactional
	public void record(NetworkNode node, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks) {
		record(node, cpuPercent, memoryMb, diskFreeMb, runningTasks, null, null);
	}

	/**
	 * A amostra com o I/O de disco medido (brief §8.2). Separada da assinatura acima pelo mesmo
	 * motivo dos outros contratos deste módulo: os chamadores que não medem disco continuam vendo o
	 * nó "sem medição", e não "com 0 IOPS".
	 */
	@Transactional
	public void record(NetworkNode node, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks, Double diskIops, Double diskLatencyMs) {
		record(node, cpuPercent, memoryMb, diskFreeMb, runningTasks, diskIops, diskLatencyMs, null);
	}

	/**
	 * A amostra com a temperatura da máquina (brief §8.2, "quando disponível"). O nó sem sensor
	 * legível manda nulo, e o ponto do minuto fica sem temperatura — o gráfico mostra a lacuna em vez
	 * de uma linha em 0 °C que ninguém mediu.
	 */
	@Transactional
	public void record(NetworkNode node, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks, Double diskIops, Double diskLatencyMs, java.math.BigDecimal temperatureC) {
		record(node, cpuPercent, memoryMb, diskFreeMb, runningTasks, diskIops, diskLatencyMs, temperatureC, null);
	}

	/**
	 * A amostra com as threads do processo do runtime (brief §8.2, "Runtime"). Nulo é "não medido" —
	 * runtime antigo (anterior à V388) ou `/proc` inacessível —, e não 0, que afirmaria um processo
	 * sem thread nenhuma.
	 */
	public void record(NetworkNode node, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks, Double diskIops, Double diskLatencyMs, java.math.BigDecimal temperatureC,
			Integer threads) {
		record(node, cpuPercent, memoryMb, diskFreeMb, runningTasks, diskIops, diskLatencyMs, temperatureC, threads,
			null, null);
	}

	/**
	 * A amostra com swap em uso e pressão de memória (brief §8.2, "Sistema operacional"). Nulo é "não
	 * medido" — kernel sem swap ou sem PSI, runtime anterior à V389 —, e nunca 0, que afirmaria
	 * "nenhum swap em uso" e "nenhuma tarefa esperou memória" sem ninguém ter lido o arquivo.
	 */
	public void record(NetworkNode node, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks, Double diskIops, Double diskLatencyMs, java.math.BigDecimal temperatureC,
			Integer threads, Long swapUsedMb, Double memoryPressurePercent) {
		Instant now = clock.instant();
		Instant minute = now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
		repository.saveSample(new Sample(node.id(), minute, cpuPercent, memoryMb, diskFreeMb, runningTasks, null,
			node.status().name(), null, diskIops, diskLatencyMs, temperatureC, threads, swapUsedMb,
			memoryPressurePercent));
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

	/** O quadro do nó: série (antiga → nova) e console (nova → antiga), com as quatro dimensões de saúde. */
	@Transactional(readOnly = true)
	public Panel panel(String nodeId, int sampleLimit, int logLimit) {
		List<Sample> samples = repository.samples(nodeId, clamp(sampleLimit, SAMPLE_KEEP));
		List<LogLine> logs = repository.logs(nodeId, clamp(logLimit, LOG_KEEP));
		Instant now = clock.instant();
		return new Panel(nodeId, now, List.copyOf(samples), logs.stream().map(View::of).toList(),
			summarize(samples), logLevels(logs), freshness(samples, now), health(nodeId, now),
			latency(nodeId, now));
	}

	private TaskLatency latency(String nodeId, Instant now) {
		var distribution = new com.socialcoisa.rede.network.domain.TaskLatency(
			repository.latencySamples(nodeId, now, LATENCY_WINDOW_HOURS),
			repository.retrySamples(nodeId, now, LATENCY_WINDOW_HOURS));
		return new TaskLatency(distribution.p50Ms(), distribution.p95Ms(), distribution.p99Ms(), distribution.total(),
			distribution.retries(), distribution.retriesP50(), distribution.retriesP95(), distribution.retriesP99());
	}

	/**
	 * As quatro dimensões de saúde do nó (brief §8.8), com o atraso de sincronização que a aplicação
	 * considera.
	 *
	 * <p>A leitura do atraso vem do catálogo de versões, e não do serviço de sincronização: o
	 * atraso é a mesma conta ({@code countBehind} por tipo) e assim o quadro não depende do
	 * serviço de conteúdo para desenhar saúde. Nó que sumiu do banco entre a leitura da tela e esta
	 * chamada não ganha saúde inventada: o chamador já exigiu que existisse.</p>
	 */
	NodeHealth health(String nodeId, Instant now) {
		NetworkNode node = nodes.findById(nodeId).orElse(null);
		if (node == null) {
			return null;
		}
		long behind = 0;
		for (com.socialcoisa.rede.network.domain.ContentKind kind
				: com.socialcoisa.rede.network.domain.ContentKind.values()) {
			behind += versions.countBehind(nodeId, kind);
		}
		return NodeHealth.of(node, behind, now);
	}

	/**
	 * Quão recente é o último ponto da série — e o que isso permite afirmar.
	 *
	 * <p>O brief §8.7 exige que o painel <b>distinga ausência de dados, nó offline e métrica
	 * efetivamente zerada</b>. Sem este bloco, o quadro devolvia a série e nada mais: uma janela com
	 * um ponto de duas horas atrás desenhava igual a uma com pontos deste minuto, e "0% de CPU" era
	 * indistinguível de "não mediu". Os três casos são diferentes e a tela precisa poder separá-los:</p>
	 *
	 * <ul>
	 * <li>{@code NO_SAMPLES} — o nó nunca empurrou amostra. Não há dado; nada se sabe;</li>
	 * <li>{@code FRESH} — o último ponto cabe na janela de "vivo" (a mesma do scheduler e do
	 * painel: 180 s). É a medida vigente;</li>
	 * <li>{@code STALE} — há ponto, mas o último é mais velho que a janela. O nó pode estar fora, ou
	 * pode estar online sem empurrar há um tempo; o painel mostra o número <b>com a idade</b>, e
	 * {@code lastSampleAt} diz de quando ele é.</li>
	 * </ul>
	 *
	 * <p>{@code ageSeconds} é nulo sem amostra: 0 diria "medido agora", que é o oposto do caso vazio.
	 * A idade é calculada no servidor, do relógio dele — o runtime do nó não decide quão velho é o
	 * próprio dado.</p>
	 */
	static Freshness freshness(List<Sample> samples, Instant now) {
		if (samples == null || samples.isEmpty()) {
			return new Freshness("NO_SAMPLES", null, null);
		}
		// A série vem do mais novo para o mais antigo: o primeiro é o último ponto medido.
		Instant last = samples.get(0).sampledAt();
		long age = Math.max(0, java.time.Duration.between(last, now).getSeconds());
		return new Freshness(age <= ALIVE_SECONDS ? "FRESH" : "STALE", last, age);
	}

	/**
	 * A idade da série. {@code state} é o que a tela usa para decidir se pode afirmar algo sobre o
	 * nó; {@code lastSampleAt} e {@code ageSeconds} dizem de quando é o número mostrado.
	 */
	public record Freshness(String state, Instant lastSampleAt, Long ageSeconds) {
	}

	/**
	 * Agregados do quadro — os mesmos números do topo do painel de monitoração, calculados sobre a
	 * janela do nó. Média só sobre o que foi <b>medido</b>: um nó com metade dos heartbeats sem
	 * disco não pode ter a média puxada para baixo por zeros que ninguém mediu.
	 *
	 * <p>{@code swapUsedMbLatest} e {@code memoryPressurePercentLatest} são o último valor <b>medido</b>
	 * da janela (brief §8.2, "Sistema operacional"), e não o da amostra mais nova: os dois ficam nulos
	 * quando o kernel não os expõe, e o ponto mais recente sem eles não apaga o último número que
	 * existiu.</p>
	 */
	record Summary(Double cpuAvgPercent, Double cpuMaxPercent, Integer memoryMbLatest, Long diskFreeMbLatest,
			Integer runningTasksLatest, int sampleCount, Double diskIopsLatest, Double diskLatencyMsLatest,
			java.math.BigDecimal temperatureCLatest, Long swapUsedMbLatest, Double memoryPressurePercentLatest) {
	}

	record View(String id, String at, String level, String message) {
		static View of(LogLine line) {
			return new View(line.id(), line.loggedAt().toString(), line.level(), line.message());
		}
	}

	/** O que a página do nó desenha. {@code generatedAt} deixa claro que o quadro é do instante. */
	public record Panel(String nodeId, Instant generatedAt, List<Sample> series, List<View> console,
			Summary summary, java.util.Map<String, Long> consoleLevels, Freshness freshness, NodeHealth health,
			TaskLatency latency) {
	}

	/**
	 * A distribuição de latência do nó na janela. {@code samplesInWindow} é quantas tarefas entraram
	 * nos percentis: um p99 sobre 10 tarefas é uma anedota, e o painel precisa poder dizer isso em vez
	 * de mostrar um número com três algarismos de confiança que não tem.
	 */
	/**
	 * {@code retriesInWindow} é quantas das tarefas concluídas tinham sido devolvidas à fila antes
	 * (brief §8.2, "retentativas"); {@code retriesP*} a distribuição de TENTATIVAS perdidas até
	 * concluir — separada da duração de propósito (tempo até a falha não é tempo de execução).
	 * {@code null} quando nenhuma tarefa da janela precisou de retentativa: zero afirmaria "todas
	 * concluíram na primeira tentativa", que é outra medida.
	 */
	public record TaskLatency(Long p50Ms, Long p95Ms, Long p99Ms, long samplesInWindow, long retriesInWindow,
			Long retriesP50, Long retriesP95, Long retriesP99) {
	}

	/**
	 * O último valor <b>medido</b> de uma coluna da série — não o da amostra mais nova.
	 *
	 * <p>Medida que o kernel não expõe (swap num container sem swap, PSI num kernel anterior a 4.20)
	 * vem nula em quase todo heartbeat; pegar o valor da amostra mais nova mostraria "não medido" para
	 * uma máquina que mediu swap em uso dez minutos atrás e não mudou desde então. O número mostrado é
	 * o último que alguém mediu, com a idade da série ao lado ({@link Freshness}).</p>
	 */
	static <T> T latestMeasured(List<Sample> samples, java.util.function.Function<Sample, T> campo) {
		for (int i = samples.size() - 1; i >= 0; i--) {
			T valor = campo.apply(samples.get(i));
			if (valor != null) {
				return valor;
			}
		}
		return null;
	}

	private static Summary summarize(List<Sample> samples) {
		List<Double> cpus = samples.stream().map(Sample::cpuPercent).filter(java.util.Objects::nonNull).toList();
		Double avg = cpus.isEmpty() ? null : cpus.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		Double max = cpus.isEmpty() ? null : cpus.stream().mapToDouble(Double::doubleValue).max().orElse(0);
		Sample latest = samples.isEmpty() ? null : samples.get(samples.size() - 1);
		return new Summary(avg == null ? null : Math.round(avg * 10) / 10.0,
			max == null ? null : Math.round(max * 10) / 10.0, latest == null ? null : latest.memoryMb(),
			latest == null ? null : latest.diskFreeMb(), latest == null ? null : latest.runningTasks(),
			samples.size(), latest == null ? null : latest.diskIops(),
			latest == null ? null : latest.diskLatencyMs(), latest == null ? null : latest.temperatureC(),
			latestMeasured(samples, Sample::swapUsedMb), latestMeasured(samples, Sample::memoryPressurePercent));
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