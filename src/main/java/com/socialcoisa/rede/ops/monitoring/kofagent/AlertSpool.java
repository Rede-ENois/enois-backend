package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Write-ahead store for critical messages: a critical message is written here before the first
 * send attempt and deleted only after Telegram confirms it. Whatever is still here after a crash,
 * a restart or a long outage is replayed in order once the channel works again.
 *
 * <p>One small JSON file per message, named {@code <epochMillis>-<sequence>.json} so the directory
 * listing is the replay order. Writes go to a temp file and are moved into place, so a crash never
 * leaves half a message. Messages Telegram refuses for good move to {@code dead/}.</p>
 */
final class AlertSpool {

	private static final Logger LOG = LoggerFactory.getLogger(AlertSpool.class);

	private final Path dir;
	private final Path dead;
	private final int maxFiles;
	private final ObjectMapper mapper;
	private final AtomicLong sequence = new AtomicLong();

	AlertSpool(Path dir, int maxFiles, ObjectMapper mapper) {
		this.dir = dir;
		this.dead = dir.resolve("dead");
		this.maxFiles = maxFiles;
		this.mapper = mapper;
	}

	/** Persists the message; returns its key, or {@code null} when the spool is full or the disk fails. */
	String write(Outgoing message, int currentSize) {
		if (currentSize >= maxFiles) {
			return null;
		}
		String key = String.format("%013d-%06d.json", message.createdAt().toEpochMilli(),
			sequence.incrementAndGet() % 1_000_000);
		try {
			Files.createDirectories(dir);
			Path tmp = dir.resolve(key + ".tmp");
			Files.writeString(tmp, mapper.writeValueAsString(Map.of("severity", message.severity().name(), "text",
				message.text(), "silent", message.silent(), "createdAt", message.createdAt().toString())),
				StandardCharsets.UTF_8);
			Files.move(tmp, dir.resolve(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			return key;
		}
		catch (IOException | RuntimeException failure) {
			LOG.warn("kof_agent_spool_write_failed type={}", failure.getClass().getSimpleName());
			return null;
		}
	}

	void delete(String key) {
		try {
			Files.deleteIfExists(dir.resolve(key));
		}
		catch (IOException failure) {
			LOG.warn("kof_agent_spool_delete_failed type={}", failure.getClass().getSimpleName());
		}
	}

	void deadLetter(String key) {
		try {
			Files.createDirectories(dead);
			Files.move(dir.resolve(key), dead.resolve(key), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException failure) {
			delete(key);
		}
	}

	/** Oldest spooled messages not already in memory, up to {@code limit}. */
	List<Outgoing> load(Set<String> exclude, int limit) {
		List<Outgoing> out = new ArrayList<>();
		for (String key : keys()) {
			if (out.size() >= limit) {
				break;
			}
			if (exclude.contains(key)) {
				continue;
			}
			try {
				JsonNode json = mapper.readTree(Files.readString(dir.resolve(key), StandardCharsets.UTF_8));
				out.add(new Outgoing(key, Severity.of(json.path("severity").asString("ERROR")),
					json.path("text").asString(""), true, json.path("silent").asBoolean(false),
					Instant.parse(json.path("createdAt").asString(Instant.EPOCH.toString())), 0));
			}
			catch (IOException | RuntimeException failure) {
				LOG.warn("kof_agent_spool_unreadable type={}", failure.getClass().getSimpleName());
				deadLetter(key);
			}
		}
		return out;
	}

	int size() {
		return keys().size();
	}

	private List<String> keys() {
		List<String> keys = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return keys;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
			for (Path path : stream) {
				keys.add(path.getFileName().toString());
			}
		}
		catch (IOException failure) {
			LOG.warn("kof_agent_spool_list_failed type={}", failure.getClass().getSimpleName());
		}
		keys.sort(null);
		return keys;
	}
}
