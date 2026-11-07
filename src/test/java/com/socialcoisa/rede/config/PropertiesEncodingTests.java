package com.socialcoisa.rede.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

/**
 * Spring Boot reads {@code .properties} files as ISO-8859-1, so a UTF-8 "Énóis" in a value reaches the
 * application as "Ã‰nÃ³is" (this is how the Telegram channel header broke in production). Values must
 * be ASCII, with {@code \\uXXXX} escapes for anything else; comments may stay in any language.
 */
class PropertiesEncodingTests {

	private static final List<Path> ROOTS = List.of(Path.of("src/main/resources"), Path.of("src/test/resources"));

	@Test
	void everyPropertyValueIsAscii() throws IOException {
		List<String> offending = new ArrayList<>();
		for (Path file : propertiesFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).stripLeading();
				if (!line.startsWith("#") && !line.startsWith("!") && !line.chars().allMatch(c -> c < 0x80)) {
					offending.add(file + ":" + (i + 1));
				}
			}
		}
		assertThat(offending).as("use \\uXXXX escapes in property values").isEmpty();
	}

	@Test
	void theChannelHeaderKeepsItsAccentsThroughSpringsLoader() throws IOException {
		var source = new PropertiesPropertySourceLoader()
			.load("application", new FileSystemResource("src/main/resources/application.properties")).getFirst();

		assertThat(source.getProperty("rede.kof-agent.service-name"))
			.isEqualTo("${KOF_AGENT_SERVICE_NAME:API Énóis}");
	}

	private static List<Path> propertiesFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		for (Path root : ROOTS) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(path -> path.getFileName().toString().endsWith(".properties")).forEach(files::add);
			}
		}
		assertThat(files).isNotEmpty();
		return files;
	}
}
