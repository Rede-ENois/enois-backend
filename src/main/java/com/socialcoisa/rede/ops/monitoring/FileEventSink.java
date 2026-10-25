package com.socialcoisa.rede.ops.monitoring;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Eventos de monitorabilidade em arquivo JSONL na VPS, sem passar pelo banco.
 *
 * <p>Um arquivo por dia ({@code events-AAAA-MM-DD.jsonl}) com uma linha JSON por evento
 * (formato estável, campos do {@link MonitoringService.EventView}). A rotação apaga os dias
 * além da retenção. A busca lê os dias mais novos primeiro, do fim para o começo, com predicado:
 * cada linha é testada e descartada sem acumular — sem carregar o arquivo inteiro.</p>
 */
@Component
class FileEventSink {

	private static final Logger LOG = LoggerFactory.getLogger(FileEventSink.class);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final int MAX_LINE = 16_384;

	private final Path dir;
	private final ObjectMapper mapper;
	private final int retentionDays;

	private final Object writeLock = new Object();
	private volatile String openDay;
	private volatile BufferedWriter writer;

	FileEventSink(
			@Value("${rede.monitoring.file-dir:/app/data/monitoring}") String dir,
			@Value("${rede.monitoring.retention-days:7}") int retentionDays,
			ObjectMapper mapper) {
		this.dir = Paths.get(dir);
		this.mapper = mapper;
		this.retentionDays = Math.max(1, retentionDays);
	}

	/** Grava o evento; falha de disco nunca derruba a requisição. */
	void append(MonitoringService.EventView event) {
		String day = DAY.format(LocalDate.now(ZoneOffset.UTC));
		try {
			BufferedWriter out = writerFor(day);
			out.write(toJson(event));
			out.newLine();
			out.flush();
		}
		catch (IOException failure) {
			closeQuietly();
			LOG.warn("monitoramento em arquivo indisponivel: {}", failure.getMessage());
		}
	}

	/** Fecha o dia anterior e apaga dias além da retenção. */
	void rotate() {
		String today = DAY.format(LocalDate.now(ZoneOffset.UTC));
		synchronized (writeLock) {
			if (!today.equals(openDay)) {
				closeQuietly();
			}
		}
		if (!Files.isDirectory(dir)) {
			return;
		}
		LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "events-*.jsonl")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				String day = name.length() >= 17 ? name.substring(7, 17) : "";
				try {
					if (!day.isBlank() && LocalDate.parse(day, DAY).isBefore(cutoff)) {
						Files.deleteIfExists(file);
					}
				}
				catch (RuntimeException ignored) {
					// Nome fora do padrão: não é nosso, não toca.
				}
			}
		}
		catch (IOException ignored) {
			// Rotação é melhor esforço; o próximo ciclo tenta de novo.
		}
	}

	/** Busca com predicado: dias mais novos primeiro, do fim para o começo, até o limite. */
	List<MonitoringService.EventView> search(java.util.function.Predicate<MonitoringService.EventView> match,
			int limit) {
		List<MonitoringService.EventView> found = new ArrayList<>(Math.max(1, limit));
		for (Path file : daysNewestFirst()) {
			scanBackward(file, match, limit, found);
			if (found.size() >= limit) {
				break;
			}
		}
		return found;
	}

	/** Lê os dias mais novos primeiro, do fim para o começo, até o limite. */
	List<MonitoringService.EventView> read(int limit) {
		return search(event -> true, limit);
	}

	private List<Path> daysNewestFirst() {
		List<Path> files = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return files;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "events-*.jsonl")) {
			for (Path file : stream) {
				files.add(file);
			}
		}
		catch (IOException ignored) {
			return files;
		}
		files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
		return files;
	}

	private void scanBackward(Path file, java.util.function.Predicate<MonitoringService.EventView> match,
			int limit, List<MonitoringService.EventView> found) {
		List<String> tail = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				tail.add(line);
				// Janela deslizante: guarda só o fim do arquivo; linha que sai da janela foi
				// testada mais tarde, do fim para o começo — nenhuma linha escapa do predicado.
				while (tail.size() > SCAN_WINDOW) {
					MonitoringService.EventView old = fromJson(tail.remove(0));
					if (old != null && match.test(old) && found.size() < limit) {
						found.add(old);
					}
					if (found.size() >= limit) {
						return;
					}
				}
			}
		}
		catch (IOException ignored) {
			return;
		}
		for (int i = tail.size() - 1; i >= 0 && found.size() < limit; i--) {
			MonitoringService.EventView event = fromJson(tail.get(i));
			if (event != null && match.test(event)) {
				found.add(event);
			}
		}
	}

	private static final int SCAN_WINDOW = 2000;

	private BufferedWriter writerFor(String day) throws IOException {
		synchronized (writeLock) {
			if (writer != null && day.equals(openDay)) {
				return writer;
			}
			closeQuietly();
			Files.createDirectories(dir);
			writer = Files.newBufferedWriter(dir.resolve("events-" + day + ".jsonl"),
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			openDay = day;
			return writer;
		}
	}

	private void closeQuietly() {
		synchronized (writeLock) {
			if (writer == null) {
				return;
			}
			try {
				writer.close();
			}
			catch (IOException ignored) {
				// Fechar é melhor esforço.
			}
			writer = null;
			openDay = null;
		}
	}

	private String toJson(MonitoringService.EventView event) {
		StringBuilder json = new StringBuilder(512);
		json.append("{\"id\":").append(quoted(event.id()));
		json.append(",\"createdAt\":").append(quoted(event.createdAt()));
		json.append(",\"level\":").append(quoted(event.level()));
		json.append(",\"type\":").append(quoted(event.type()));
		json.append(",\"message\":").append(quoted(event.message()));
		json.append(",\"requestId\":").append(quoted(event.requestId()));
		json.append(",\"method\":").append(quoted(event.method()));
		json.append(",\"path\":").append(quoted(event.path()));
		json.append(",\"route\":").append(quoted(event.route()));
		json.append(",\"status\":").append(event.status() == null ? "null" : event.status().toString());
		json.append(",\"durationMs\":")
			.append(event.durationMs() == null ? "null" : event.durationMs().toString());
		json.append(",\"userId\":").append(quoted(event.userId()));
		json.append(",\"clientIp\":").append(quoted(event.clientIp()));
		json.append(",\"deviceId\":").append(quoted(event.deviceId()));
		json.append(",\"metadata\":").append(quoted(event.metadata()));
		json.append('}');
		String line = json.toString();
		return line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line;
	}

	private MonitoringService.EventView fromJson(String line) {
		try {
			JsonNode node = mapper.readTree(line);
			return new MonitoringService.EventView(
				text(node, "id"), text(node, "createdAt"), text(node, "level"), text(node, "type"),
				text(node, "message"), text(node, "requestId"), text(node, "method"), text(node, "path"),
				text(node, "route"),
				node.has("status") && !node.get("status").isNull() ? (Integer) node.get("status").asInt() : null,
				node.has("durationMs") && !node.get("durationMs").isNull()
					? (Long) node.get("durationMs").asLong()
					: null,
				text(node, "userId"), text(node, "clientIp"), text(node, "deviceId"),
				text(node, "metadata"));
		}
		catch (RuntimeException failure) {
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		if (!node.has(field) || node.get(field).isNull()) {
			return null;
		}
		return node.get(field).asString();
	}

	private static String quoted(String value) {
		if (value == null) {
			return "null";
		}
		StringBuilder out = new StringBuilder(value.length() + 2);
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
		return out.toString();
	}

	static Instant instantOf(MonitoringService.EventView event) {
		try {
			return Instant.parse(event.createdAt());
		}
		catch (RuntimeException failure) {
			return Instant.EPOCH;
		}
	}
}
