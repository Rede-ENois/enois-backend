package com.socialcoisa.rede.ops.monitoring;

import java.io.File;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.socialcoisa.rede.shared.web.RequestObservabilityFilter;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Monitorabilidade do painel admin: carga, latência, recursos, eventos e logs.
 *
 * <p>As métricas são em memória (séries de 60 minutos) e por pod; os eventos/erros/logs vão para
 * arquivo JSONL na VPS de forma assíncrona — a observabilidade NUNCA usa o pool do banco, para não
 * concorrer com as requisições dos usuários. Nada bloqueia a requisição: a fila tem teto e o
 * excedente é contado em {@code droppedEvents}. Dados sensíveis são redigidos antes de persistir.
 * Só 5xx vira evento; 4xx conta métrica (não vira linha) para o painel não afogar em ruído.</p>
 */
@Service
public class MonitoringService {

	private static final int RECENT_LIMIT = 500;
	private static final int QUEUE_LIMIT = 5000;
	private static final int MAX_MESSAGE = 1000;
	private static final long MINUTE = 60_000L;
	private static final long SLOW_MS = 2000L;

	private final Clock clock;
	private final int maxEventsPerMinute;
	private final ObjectProvider<DataSource> dataSource;
	private final FileEventSink sink;
	private final ArrayBlockingQueue<EventRow> queue = new ArrayBlockingQueue<>(QUEUE_LIMIT);
	private final ConcurrentLinkedDeque<EventView> recent = new ConcurrentLinkedDeque<>();
	private final ConcurrentHashMap<Long, MinuteMetrics> minutes = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, LongAdder> seconds = new ConcurrentHashMap<>();
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong requestsTotal = new AtomicLong();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final AtomicInteger peakInFlight = new AtomicInteger();
	private long eventMinute = -1;
	private int eventCount;
	// Amostra anterior do cpu.stat do container: a porcentagem de uso é o delta entre chamadas.
	private volatile long ultimoCpuUsoMicros = -1;
	private volatile long ultimoCpuAmostraMillis = -1;
	private volatile List<CountView> levelCounts = List.of();
	private volatile List<CountView> typeCounts = List.of();
	private volatile long countsAt;

	MonitoringService(Clock clock, ObjectProvider<DataSource> dataSource, FileEventSink sink,
			@Value("${rede.monitoring.max-events-per-minute:1000}") int maxEventsPerMinute) {
		this.clock = clock;
		this.dataSource = dataSource;
		this.sink = sink;
		this.maxEventsPerMinute = Math.max(1, maxEventsPerMinute);
	}

	// ------------------------------------------------------------------ ingestão

	public void requestStarted() {
		int current = inFlight.incrementAndGet();
		peakInFlight.accumulateAndGet(current, Math::max);
	}

	/**
	 * Requisição abortada pelo cliente (aba fechada, vídeo interrompido): só encerra o in-flight.
	 * Contar como 5xx/4xx ou gravar evento transformaria desconexão normal em alarme falso.
	 */
	public void recordClientDisconnect() {
		inFlight.updateAndGet(value -> Math.max(0, value - 1));
	}

	/** Chamado pelo filtro: métricas de TODA requisição; só 5xx vira evento. */
	public void recordRequest(String method, String path, String route, int status, long durationMs,
			String requestId, String userId, String clientIp, String deviceId) {
		inFlight.updateAndGet(value -> Math.max(0, value - 1));
		requestsTotal.incrementAndGet();
		long now = clock.millis();
		long bucket = now / MINUTE;
		MinuteMetrics metrics = minutes.computeIfAbsent(bucket, key -> new MinuteMetrics());
		metrics.requests.increment();
		if (status >= 500) {
			metrics.errors5xx.increment();
		}
		else if (status >= 400) {
			metrics.errors4xx.increment();
		}
		else if (status >= 300) {
			metrics.errors3xx.increment();
		}
		else {
			metrics.errors2xx.increment();
		}
		if (status == 429) {
			metrics.rateLimited.increment();
		}
		if (durationMs >= SLOW_MS) {
			metrics.slow.increment();
		}
		metrics.durations.addLast(durationMs);
		while (metrics.durations.size() > 2000) {
			metrics.durations.pollFirst();
		}
		metrics.maxDuration.accumulateAndGet(durationMs, Math::max);
		metrics.methods.computeIfAbsent(method == null ? "?" : method, key -> new LongAdder()).increment();
		metrics.routes.computeIfAbsent(route == null ? "UNMATCHED" : route, key -> new LongAdder()).increment();
		seconds.computeIfAbsent(now / 1000, key -> new LongAdder()).increment();
		// TODO: request inteira no painel (200, 4xx, 5xx — tudo). O nível reflete o status:
		// 5xx ERROR, 4xx WARN, resto INFO. A fila tem teto e o painel pagina, então o volume
		// extra não derruba nada.
		String level = status >= 500 ? "ERROR" : status >= 400 ? "WARN" : "INFO";
		String message = method + " " + (route == null ? path : route) + " -> " + status;
		enqueue(new EventRow(id(), clock.instant(), level, "REQUEST", message, requestId, method, path,
			route, status, durationMs, userId, clientIp, deviceId, null));
	}

	// Exceções gravam com o IP da requisição atual (via MDC do filtro): é o IP de origem
	// da tentativa que gerou o erro, não do erro em si.
	public void recordException(int status, String code, String message, String method, String path,
			String requestId, String userId) {
		recordException(status, code, message, method, path, requestId, userId, null);
	}

	/** Exceções com pilha: o painel mostra a causa sem expor corpo de requisição nem segredo. */
	public void recordException(int status, String code, String message, String method, String path,
			String requestId, String userId, Throwable failure) {
		enqueue(new EventRow(id(), clock.instant(), status >= 500 ? "ERROR" : "WARN", "EXCEPTION",
			code + ": " + safe(message), requestId, method, path, null, status, null, userId,
			org.slf4j.MDC.get(RequestObservabilityFilter.MDC_CLIENT_IP), null,
			safeStackTrace(failure, 4000)));
	}

	public void recordEvent(String level, String type, String message, Map<String, Object> metadata) {
		enqueue(new EventRow(id(), clock.instant(), normalizeLevel(level), normalizeType(type), safe(message),
			null, null, null, null, null, null, null, null, null, metadata == null ? null : metadata.toString()));
	}

	public void recordLog(String level, String logger, String message, String requestId, String userId, String path) {
		String type = logger != null && logger.equals("audit") ? "AUDIT" : "LOG";
		enqueue(new EventRow(id(), clock.instant(), normalizeLevel(level), type,
			safe((logger == null ? "" : logger + ": ") + message), requestId, null, path, null, null, null,
			userId, null, null, null));
	}

	private synchronized boolean allowEvent() {
		long minute = clock.millis() / MINUTE;
		if (eventMinute != minute) {
			eventMinute = minute;
			eventCount = 0;
		}
		if (eventCount >= maxEventsPerMinute) {
			return false;
		}
		eventCount++;
		return true;
	}

	private void enqueue(EventRow row) {
		if (!allowEvent()) {
			dropped.incrementAndGet();
			return;
		}
		EventView view = row.view();
		recent.addFirst(view);
		while (recent.size() > RECENT_LIMIT) {
			recent.pollLast();
		}
		if (!queue.offer(row)) {
			dropped.incrementAndGet();
		}
	}

	@Scheduled(fixedDelay = 500)
	void flush() {
		List<EventRow> batch = new ArrayList<>(200);
		queue.drainTo(batch, 200);
		if (batch.isEmpty()) {
			return;
		}
		// Arquivo na VPS, fora do pool do banco: a observabilidade nunca disputa conexão
		// com as requisições dos usuários.
		for (EventRow row : batch) {
			sink.append(row.view());
		}
	}

	/** Amostra CPU/heap por minuto para o gráfico de recursos. */
	@Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
	void sampleResources() {
		long bucket = clock.millis() / MINUTE;
		MinuteMetrics metrics = minutes.computeIfAbsent(bucket, key -> new MinuteMetrics());
		metrics.cpuPercent = Math.round(processCpuPercent());
		metrics.heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}

	@Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
	void pruneMinutes() {
		long nowBucket = clock.millis() / MINUTE;
		for (Long bucket : minutes.keySet()) {
			if (bucket < nowBucket - 61) {
				minutes.remove(bucket);
			}
		}
		long nowSecond = clock.millis() / 1000;
		for (Long second : seconds.keySet()) {
			if (second < nowSecond - 120) {
				seconds.remove(second);
			}
		}
	}

	@Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
	void purgeOld() {
		// Rotação por dia no próprio sink: fecha o dia anterior e apaga além da retenção.
		sink.rotate();
	}

	// ------------------------------------------------------------------ leitura

	public OverviewView overview() {
		long nowBucket = clock.millis() / MINUTE;
		long nowSecond = clock.millis() / 1000;
		for (Long bucket : minutes.keySet()) {
			if (bucket < nowBucket - 61) {
				minutes.remove(bucket);
			}
		}
		for (Long second : seconds.keySet()) {
			if (second < nowSecond - 120) {
				seconds.remove(second);
			}
		}

		List<MinutePoint> series = new ArrayList<>(60);
		long totalRequests = 0;
		long total2xx = 0;
		long total3xx = 0;
		long total4xx = 0;
		long total5xx = 0;
		long totalSlow = 0;
		long totalRateLimited = 0;
		List<Long> latencySamples = new ArrayList<>();
		Map<String, Long> methods = new LinkedHashMap<>();
		for (long bucket = nowBucket - 59; bucket <= nowBucket; bucket++) {
			MinuteMetrics metrics = minutes.get(bucket);
			if (metrics == null) {
				series.add(new MinutePoint(Instant.ofEpochMilli(bucket * MINUTE).toString(), 0, 0, 0, 0, 0,
					0, 0, 0, 0, 0, 0, 0));
				continue;
			}
			long requests = metrics.requests.sum();
			long e2 = metrics.errors2xx.sum();
			long e3 = metrics.errors3xx.sum();
			long e4 = metrics.errors4xx.sum();
			long e5 = metrics.errors5xx.sum();
			totalRequests += requests;
			total2xx += e2;
			total3xx += e3;
			total4xx += e4;
			total5xx += e5;
			totalSlow += metrics.slow.sum();
			totalRateLimited += metrics.rateLimited.sum();
			if (bucket >= nowBucket - 4) {
				latencySamples.addAll(metrics.durations);
			}
			metrics.methods.forEach((method, count) -> methods.merge(method, count.sum(), Long::sum));
			series.add(new MinutePoint(Instant.ofEpochMilli(bucket * MINUTE).toString(), requests, e2, e3, e4,
				e5, p95(metrics.durations), p99(metrics.durations), metrics.maxDuration.get(), metrics.slow.sum(),
				metrics.rateLimited.sum(), Math.max(0, metrics.cpuPercent),
				metrics.heapUsed <= 0 ? 0 : metrics.heapUsed / 1024 / 1024));
		}

		MinutePoint last = series.get(series.size() - 1);
		double errorRate = totalRequests == 0 ? 0 : (total4xx + total5xx) * 100.0 / totalRequests;

		Map<String, Long> routes = new LinkedHashMap<>();
		for (long bucket = Math.max(nowBucket - 15, 0); bucket <= nowBucket; bucket++) {
			MinuteMetrics metrics = minutes.get(bucket);
			if (metrics == null) {
				continue;
			}
			metrics.routes.forEach((route, count) -> routes.merge(route, count.sum(), Long::sum));
		}
		List<CountView> topRoutes = routes.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.limit(10)
			.map(entry -> new CountView(entry.getKey(), entry.getValue()))
			.toList();
		List<CountView> methodCounts = methods.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.map(entry -> new CountView(entry.getKey(), entry.getValue()))
			.toList();

		long last10s = 0;
		for (long second = nowSecond - 9; second <= nowSecond; second++) {
			LongAdder count = seconds.get(second);
			if (count != null) {
				last10s += count.sum();
			}
		}

		refreshCounts();
		ResourceView resources = resources();
		return new OverviewView(clock.instant().toString(),
			ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
			last.requests(), last.errors4xx(), last.errors5xx(), errorRate, p95(latencySamples),
			dropped.get(), series, levelCounts, typeCounts, topRoutes, recentEvents(100),
			new JvmView(resources.heapUsedBytes(), resources.heapMaxBytes(), resources.threads(),
				resources.processors()),
			last10s / 10.0, inFlight.get(), peakInFlight.get(), requestsTotal.get(),
			p50(latencySamples), p99(latencySamples), max(latencySamples), last.slowRequests(),
			last.rateLimited(), List.of(new CountView("2xx", total2xx), new CountView("3xx", total3xx),
				new CountView("4xx", total4xx), new CountView("5xx", total5xx)),
			methodCounts, resources);
	}

	public List<EventView> events(String level, String type, String q, String from, String to, String before, int limit) {
		// Lê do arquivo na VPS (sem banco): dias mais novos primeiro, filtrando em memória e
		// parando quando o limite do painel é atingido.
		String wantLevel = StringUtils.hasText(level) ? level.trim().toUpperCase(Locale.ROOT) : null;
		String wantType = StringUtils.hasText(type) ? type.trim().toUpperCase(Locale.ROOT) : null;
		String needle = StringUtils.hasText(q) ? q.trim().toLowerCase(Locale.ROOT) : null;
		Instant fromAt = instantOrNull(from);
		Instant toAt = instantOrNull(to);
		Instant beforeAt = instantOrNull(before);
		int teto = Math.max(1, Math.min(limit, 200));
		// Lê com folga: o filtro descarta, então busca mais linhas do que o teto final.
		List<EventView> found = new ArrayList<>(teto);
		for (EventView event : sink.read(Math.max(teto * 4, 400))) {
			if (wantLevel != null && !wantLevel.equals(event.level())) {
				continue;
			}
			if (wantType != null && !wantType.equals(event.type())) {
				continue;
			}
			if (needle != null && !needle.isBlank()) {
				String hay = ((event.message() == null ? "" : event.message()) + " "
					+ (event.path() == null ? "" : event.path())).toLowerCase(Locale.ROOT);
				if (!hay.contains(needle)) {
					continue;
				}
			}
			Instant at = FileEventSink.instantOf(event);
			if (fromAt != null && at.isBefore(fromAt)) {
				continue;
			}
			if (toAt != null && at.isAfter(toAt)) {
				continue;
			}
			if (beforeAt != null && !at.isBefore(beforeAt)) {
				continue;
			}
			found.add(event);
			if (found.size() >= teto) {
				break;
			}
		}
		return found;
	}

	private static Instant instantOrNull(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Instant.parse(value.trim());
		}
		catch (RuntimeException ignored) {
			return null;
		}
	}

	public List<EventView> recentEvents(int limit) {
		return recent.stream().limit(Math.max(1, Math.min(limit, RECENT_LIMIT))).toList();
	}

	/** Contagens de 24h a partir do buffer em memória (sem banco, sem custo por polling). */
	private void refreshCounts() {
		if (clock.millis() - countsAt < 30_000) {
			return;
		}
		synchronized (this) {
			if (clock.millis() - countsAt < 30_000) {
				return;
			}
			Map<String, Long> levels = new LinkedHashMap<>();
			Map<String, Long> types = new LinkedHashMap<>();
			for (EventView event : recent) {
				if (event.level() != null) {
					levels.merge(event.level(), 1L, Long::sum);
				}
				if (event.type() != null) {
					types.merge(event.type(), 1L, Long::sum);
				}
			}
			levelCounts = top(levels);
			typeCounts = top(types);
			countsAt = clock.millis();
		}
	}

	private static List<CountView> top(Map<String, Long> counts) {
		return counts.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
			.limit(12)
			.map(entry -> new CountView(entry.getKey(), entry.getValue()))
			.toList();
	}

	// ------------------------------------------------------------------ recursos

	private ResourceView resources() {
		Runtime runtime = Runtime.getRuntime();
		MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
		long gcCount = 0;
		long gcTime = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (gc.getCollectionCount() > 0) {
				gcCount += gc.getCollectionCount();
			}
			if (gc.getCollectionTime() > 0) {
				gcTime += gc.getCollectionTime();
			}
		}

		double processCpu = -1;
		double systemCpu = -1;
		long systemTotal = -1;
		long systemFree = -1;
		double loadAverage = -1;
		java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
		loadAverage = os.getSystemLoadAverage();
		if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
			processCpu = sun.getProcessCpuLoad() * 100;
			systemCpu = sun.getCpuLoad() * 100;
			systemTotal = sun.getTotalMemorySize();
			systemFree = sun.getFreeMemorySize();
		}
		// O MXBean reporta MEMFREE (quase zero num host ocupado, pois o cache conta como usado).
		// O número real de "quanto sobra" é o MemAvailable do /proc/meminfo.
		long[] meminfo = readMemInfo();
		if (meminfo[0] > 0) {
			systemTotal = meminfo[0];
			systemFree = meminfo[1];
		}

		File disk = new File(System.getProperty("user.dir", "/app"));
		DbPoolView pool = pool();
		return new ResourceView(memory.getHeapMemoryUsage().getUsed(), memory.getHeapMemoryUsage().getCommitted(),
			memory.getHeapMemoryUsage().getMax(), memory.getNonHeapMemoryUsage().getUsed(), gcCount, gcTime,
			threads.getThreadCount(), threads.getPeakThreadCount(), threads.getDaemonThreadCount(),
			classes.getLoadedClassCount(), processCpu, systemCpu, loadAverage, runtime.availableProcessors(),
			systemTotal, systemFree, disk.getTotalSpace(), disk.getUsableSpace(),
			containerMemoryUsed(),
			containerMemoryMax(),
			cgroupCpu(), cgroupCpuPorcentagem(), pool);
	}

	private DbPoolView pool() {
		DataSource source = dataSource.getIfAvailable();
		HikariDataSource hikari = unwrapHikari(source);
		if (hikari == null || hikari.getHikariPoolMXBean() == null) {
			return null;
		}
		var mx = hikari.getHikariPoolMXBean();
		return new DbPoolView(mx.getActiveConnections(), mx.getIdleConnections(), mx.getTotalConnections(),
			mx.getThreadsAwaitingConnection(), hikari.getMaximumPoolSize(), hikari.getMinimumIdle());
	}

	/**
	 * O DataSource pode estar embrulhado: com `bootstrap-mode=deferred` o Spring usa um
	 * `LazyConnectionDataSourceProxy` por cima do Hikari, e o instanceof direto falha — o painel
	 * mostrava 0/0 sempre. Desembrulha até chegar no Hikari real.
	 */
	private static HikariDataSource unwrapHikari(DataSource source) {
		if (source instanceof HikariDataSource hikari) {
			return hikari;
		}
		if (source == null) {
			return null;
		}
		// DataSource.unwrap é o contrato padrão e cobre os proxies do Spring (Lazy/TransactionAware);
		// o fallback por DelegatingDataSource pega versões que não implementam unwrap.
		try {
			HikariDataSource unwrapped = source.unwrap(HikariDataSource.class);
			if (unwrapped != null) {
				return unwrapped;
			}
		}
		catch (java.sql.SQLException | RuntimeException ignored) {
			// Segue para o fallback.
		}
		if (source instanceof org.springframework.jdbc.datasource.DelegatingDataSource delegating) {
			return unwrapHikari(delegating.getTargetDataSource());
		}
		return null;
	}

	private static long cgroupLong(String v2, String v1) {
		Long value = readLong(v2);
		return value != null ? value : (readLong(v1) == null ? -1 : readLong(v1));
	}

	/** Lê o /proc/meminfo: devolve {MemTotal, MemAvailable} em bytes (MemAvailable real, com cache). */
	private static long[] readMemInfo() {
		try {
			long total = 0;
			long available = 0;
			for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
				if (line.startsWith("MemTotal:")) {
					total = parseKib(line);
				}
				else if (line.startsWith("MemAvailable:")) {
					available = parseKib(line);
				}
				else if (line.startsWith("MemFree:") && available == 0) {
					available = parseKib(line);
				}
			}
			return new long[] { total * 1024, available * 1024 };
		}
		catch (Exception ignored) {
			return new long[] { -1, -1 };
		}
	}

	private static long parseKib(String line) {
		try {
			return Long.parseLong(line.replaceAll("[^0-9]", ""));
		}
		catch (RuntimeException ignored) {
			return 0;
		}
	}

	/**
	 * Uso de memória DESTE container: resolve o cgroup do próprio processo (/proc/self/cgroup) e
	 * lê o memory.current dele — com cgroup namespace de host, o caminho raiz mostraria o uso do
	 * host inteiro, não o do container.
	 */
	private static long containerMemoryUsed() {
		Long direto = readLong("/sys/fs/cgroup/memory.current");
		if (direto != null && direto > 0) {
			Long viaCgroup = readLong(cgroupPath() + "/memory.current");
			return viaCgroup != null && viaCgroup > 0 ? viaCgroup : direto;
		}
		return -1;
	}

	private static long containerMemoryMax() {
		Long direto = readLong("/sys/fs/cgroup/memory.max");
		Long viaCgroup = readLong(cgroupPath() + "/memory.max");
		long limite = viaCgroup != null ? viaCgroup : (direto != null ? direto : -1);
		// Sem limite definido ("max"), o teto real é a memória do sistema.
		return limite > 0 ? limite : readMemInfo()[0];
	}

	private static String cgroupPath() {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
				if (line.startsWith("0::")) {
					return "/sys/fs/cgroup" + line.substring(3).trim();
				}
			}
		}
		catch (Exception ignored) {
			// cgroup v1 ou indisponível: o chamador usa o caminho raiz.
		}
		return "/sys/fs/cgroup";
	}

	private static Long readLong(String path) {
		try {
			String value = Files.readString(Path.of(path)).trim();
			if ("max".equals(value)) {
				return -1L;
			}
			return Long.parseLong(value);
		}
		catch (Exception ignored) {
			return null;
		}
	}

	/**
	 * Uso de CPU do CONTAINER em porcentagem: delta do usage_usec do cgroup entre amostras,
	 * dividido pelo tempo decorrido e pelos núcleos disponíveis. A primeira chamada não tem
	 * amostra anterior e devolve -1 (a segunda, meio segundo depois, já mostra).
	 */
	private double cgroupCpuPorcentagem() {
		try {
			long usoAgora = -1;
			for (String linha : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/sys/fs/cgroup/cpu.stat"))) {
				if (linha.startsWith("usage_usec ")) {
					usoAgora = Long.parseLong(linha.substring("usage_usec ".length()).trim());
					break;
				}
			}
			if (usoAgora < 0) {
				return -1;
			}
			long agora = System.currentTimeMillis();
			double porcentagem = -1;
			if (ultimoCpuUsoMicros >= 0 && agora > ultimoCpuAmostraMillis) {
				double cpuConsumidaMillis = (usoAgora - ultimoCpuUsoMicros) / 1000.0;
				double paredeMillis = agora - ultimoCpuAmostraMillis;
				double nucleos = cgroupCpu() > 0 ? cgroupCpu() : Runtime.getRuntime().availableProcessors();
				if (nucleos > 0 && paredeMillis > 0) {
					porcentagem = (cpuConsumidaMillis / paredeMillis) / nucleos * 100.0;
				}
			}
			ultimoCpuUsoMicros = usoAgora;
			ultimoCpuAmostraMillis = agora;
			return Math.min(porcentagem, 100.0);
		}
		catch (Exception e) {
			ultimoCpuUsoMicros = -1;
			return -1;
		}
	}

	private static double cgroupCpu() {
		try {
			String[] parts = Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).trim().split("\\s+");
			if (parts.length >= 2 && !"max".equals(parts[0])) {
				return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
			}
		}
		catch (Exception ignored) {
			// cgroup v1 ou indisponível.
		}
		try {
			Long quota = readLong("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
			Long period = readLong("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
			if (quota != null && period != null && quota > 0 && period > 0) {
				return (double) quota / period;
			}
		}
		catch (Exception ignored) {
			// sem cgroup.
		}
		return -1;
	}

	private double processCpuPercent() {
		var os = ManagementFactory.getOperatingSystemMXBean();
		if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
			double value = sun.getProcessCpuLoad() * 100;
			return value < 0 ? -1 : value;
		}
		return -1;
	}

	// ------------------------------------------------------------------ util

	private static long p50(List<Long> values) {
		return percentile(values, 0.50);
	}

	private static long p95(List<Long> values) {
		return percentile(values, 0.95);
	}

	private static long p99(List<Long> values) {
		return percentile(values, 0.99);
	}

	private static long p95(Deque<Long> values) {
		return values == null ? 0 : percentile(new ArrayList<>(values), 0.95);
	}

	private static long p99(Deque<Long> values) {
		return values == null ? 0 : percentile(new ArrayList<>(values), 0.99);
	}

	private static long percentile(List<Long> values, double percentile) {
		if (values == null || values.isEmpty()) {
			return 0;
		}
		List<Long> copy = new ArrayList<>(values);
		copy.sort(Long::compareTo);
		int index = Math.min(copy.size() - 1, (int) Math.ceil(copy.size() * percentile) - 1);
		return copy.get(Math.max(0, index));
	}

	private static long max(List<Long> values) {
		return values == null || values.isEmpty() ? 0 : values.stream().mapToLong(Long::longValue).max().orElse(0);
	}

	private static String id() {
		return "evt_" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String normalizeLevel(String level) {
		if (!StringUtils.hasText(level)) {
			return "INFO";
		}
		String value = level.trim().toUpperCase(Locale.ROOT);
		return switch (value) {
			case "ERROR", "WARN", "INFO", "DEBUG" -> value;
			default -> "INFO";
		};
	}

	private static String normalizeType(String type) {
		if (!StringUtils.hasText(type)) {
			return "SYSTEM";
		}
		String value = type.trim().toUpperCase(Locale.ROOT);
		return value.length() > 32 ? value.substring(0, 32) : value;
	}

	static String safe(String message) {
		if (message == null) {
			return "";
		}
		String clean = message
			.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "***@***")
			.replaceAll("(?i)(bearer|authorization|token|refresh_token|senha|password)([=: ]+)[^ ,;]+",
				"$1$2***")
			.replaceAll("\\p{Cntrl}", " ");
		return clean.length() > MAX_MESSAGE ? clean.substring(0, MAX_MESSAGE) : clean;
	}

	/** Pilha de erro redigida para o painel: passa pelo mesmo filtro da mensagem. */
	static String safeStackTrace(Throwable failure, int maxChars) {
		if (failure == null) {
			return null;
		}
		StringBuilder pilha = new StringBuilder();
		java.util.Set<Throwable> visitados = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Throwable atual = failure;
		while (atual != null && visitados.add(atual)) {
			if (pilha.length() > 0) {
				pilha.append("Caused by: ");
			}
			pilha.append(atual.getClass().getName());
			if (atual.getMessage() != null) {
				pilha.append(": ").append(atual.getMessage());
			}
			pilha.append('\n');
			StackTraceElement[] elementos = atual.getStackTrace();
			int teto = Math.min(elementos.length, 40);
			for (int i = 0; i < teto; i++) {
				pilha.append("\tat ").append(elementos[i]).append('\n');
			}
			atual = atual.getCause();
		}
		String limpa = safe(pilha.toString());
		int teto = Math.max(500, Math.min(maxChars, 8000));
		return limpa.length() > teto ? limpa.substring(0, teto) : limpa;
	}

	private record EventRow(String id, Instant createdAt, String level, String type, String message,
			String requestId, String method, String path, String route, Integer status, Long durationMs,
			String userId, String clientIp, String deviceId, String metadata) {
		EventView view() {
			return new EventView(id, createdAt.toString(), level, type, message, requestId, method, path, route,
				status, durationMs, userId, clientIp, deviceId, metadata);
		}
	}

	private static final class MinuteMetrics {
		private final LongAdder requests = new LongAdder();
		private final LongAdder errors2xx = new LongAdder();
		private final LongAdder errors3xx = new LongAdder();
		private final LongAdder errors4xx = new LongAdder();
		private final LongAdder errors5xx = new LongAdder();
		private final LongAdder slow = new LongAdder();
		private final LongAdder rateLimited = new LongAdder();
		private final Deque<Long> durations = new ConcurrentLinkedDeque<>();
		private final AtomicLong maxDuration = new AtomicLong();
		private final ConcurrentHashMap<String, LongAdder> methods = new ConcurrentHashMap<>();
		private final ConcurrentHashMap<String, LongAdder> routes = new ConcurrentHashMap<>();
		private volatile long cpuPercent = -1;
		private volatile long heapUsed = -1;
	}

	public record EventView(String id, String createdAt, String level, String type, String message,
			String requestId, String method, String path, String route, Integer status, Long durationMs,
			String userId, String clientIp, String deviceId, String metadata) {
	}

	public record MinutePoint(String minute, long requests, long requests2xx, long requests3xx, long errors4xx,
			long errors5xx, long p95Ms, long p99Ms, long maxMs, long slowRequests, long rateLimited,
			long cpuPercent, long heapMb) {
	}

	public record CountView(String label, long count) {
	}

	public record JvmView(long heapUsedBytes, long heapMaxBytes, int threads, int processors) {
	}

	public record DbPoolView(int active, int idle, int total, int waiting, int max, int min) {
	}

	public record ResourceView(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
			long nonHeapUsedBytes, long gcCount, long gcTimeMs, int threads, int peakThreads, int daemonThreads,
			int loadedClasses, double processCpuPercent, double systemCpuPercent, double loadAverage,
			int processors, long systemMemoryTotalBytes, long systemMemoryFreeBytes, long diskTotalBytes,
			long diskFreeBytes, long cgroupMemoryUsedBytes, long cgroupMemoryMaxBytes, double cgroupCpuCores,
			double cgroupCpuPercent, DbPoolView dbPool) {
	}

	public record OverviewView(String generatedAt, long uptimeSeconds, long requestsLastMinute,
			long errors4xxLastMinute, long errors5xxLastMinute, double errorRate, long p95Ms, long droppedEvents,
			List<MinutePoint> series, List<CountView> levels24h, List<CountView> types24h,
			List<CountView> topRoutes, List<EventView> recent, JvmView jvm, double requestsPerSecond,
			int inFlight, int peakInFlight, long requestsTotal, long p50Ms, long p99Ms, long maxMs,
			long slowLastMinute, long rateLimitedLastMinute, List<CountView> statusCounts,
			List<CountView> methodCounts, ResourceView resources) {
	}
}
