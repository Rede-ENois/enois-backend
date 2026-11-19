package com.socialcoisa.rede.network.adapters.out.dump;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.network.application.StandbyDumpSource;

/**
 * O dump do banco do Control Plane pelo {@code mariadb-dump}, para a cópia no nó de reserva (V364).
 *
 * <p>Ligado por {@code rede.network.standby.dump.enabled=true}. Desligado por padrão porque exige o
 * binário e a senha do banco na mesma máquina — um processo que roda dump de produção não deve
 * existir por acidente em ambiente de desenvolvimento, e um stub que finge dump produziria cópias
 * "boas" de um banco vazio.</p>
 *
 * <p>Decisões que o comando carrega:</p>
 * <ul>
 * <li><b>{@code --skip-dump-date}</b>: sem ele, duas cópias do MESMO dado têm digests diferentes (a
 * data de geração muda), e o nó não conseguiria provar que a cópia de retomada é a mesma da
 * primeira metade.</li>
 * <li><b>{@code --single-transaction}</b>: um dump consistente sem travar a escrita do banco que
 * está atendendo.</li>
 * <li><b>{@code --no-tablespaces}</b>: o dump não precisa dos metadados de tablespace, e exigi-los
 * pediria privilégio além do de leitura.</li>
 * <li>A senha sai do ambiente ({@code MYSQL_PWD}), nunca da linha de comando: {@code ps} mostra a
 * linha de comando de todo mundo.</li>
 * <li>O processo roda com teto de memória e sai antes de um timeout longo: um dump travado num
 * banco enorme, sem fim, seguraria o endpoint da cópia indefinidamente.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "rede.network.standby.dump.enabled", havingValue = "true")
class CommandLineStandbyDumpSource implements StandbyDumpSource {

	private static final Logger LOG = LoggerFactory.getLogger(CommandLineStandbyDumpSource.class);

	private final String binary;
	private final String url;
	private final String user;
	private final String password;
	private final Duration timeout;

	CommandLineStandbyDumpSource(@Value("${rede.network.standby.dump.binary:mariadb-dump}") String binary,
			@Value("${spring.datasource.url:}") String url, @Value("${spring.datasource.username:}") String user,
			@Value("${spring.datasource.password:}") String password,
			@Value("${rede.network.standby.dump.timeout-seconds:900}") long timeoutSeconds) {
		this.binary = binary;
		this.url = url;
		this.user = user;
		this.password = password;
		this.timeout = Duration.ofSeconds(Math.max(30, timeoutSeconds));
	}

	@Override
	public byte[] dump(long offset) {
		URI jdbc = jdbcUri(url);
		String schema = jdbc.getPath() == null ? "" : jdbc.getPath().replaceFirst("^/", "");
		if (schema.isBlank()) {
			throw new IllegalStateException("a URL do banco nao nomeia o schema");
		}
		Path out = null;
		try {
			out = Files.createTempFile("enois-standby-", ".sql");
			List<String> command = new ArrayList<>(List.of(binary, "--host=" + host(jdbc),
				"--port=" + (jdbc.getPort() > 0 ? jdbc.getPort() : 3306), "--user=" + user, "--skip-dump-date",
				"--single-transaction", "--no-tablespaces", "--skip-comments", schema));
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.environment().put("MYSQL_PWD", password);
			builder.redirectErrorStream(false);
			builder.redirectOutput(out.toFile());
			Process process = builder.start();
			if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("dump excedeu " + timeout.toSeconds() + "s");
			}
			if (process.exitValue() != 0) {
				String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
				throw new IllegalStateException("mariadb-dump saiu com " + process.exitValue() + ": "
					+ (error.isEmpty() ? "sem mensagem" : error));
			}
			byte[] dump = Files.readAllBytes(out);
			if (offset <= 0) {
				return dump;
			}
			if (offset >= dump.length) {
				throw new IllegalStateException("retomada em " + offset + " além do fim do dump (" + dump.length + ")");
			}
			byte[] resumed = new byte[(int) (dump.length - offset)];
			System.arraycopy(dump, (int) offset, resumed, 0, resumed.length);
			return resumed;
		}
		catch (IOException failure) {
			throw new IllegalStateException("dump falhou: " + failure.getMessage(), failure);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("dump interrompido", interrupted);
		}
		finally {
			if (out != null) {
				try {
					Files.deleteIfExists(out);
				}
				catch (IOException ignored) {
					LOG.warn("Dump temporario nao apagado: {}", out);
				}
			}
		}
	}

	/** {@code jdbc:mariadb://host:port/schema?…} → URI com esquema válido para ler host e porta. */
	private static URI jdbcUri(String jdbcUrl) {
		if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
			throw new IllegalStateException("spring.datasource.url ausente ou fora do formato jdbc:");
		}
		return URI.create(jdbcUrl.substring("jdbc:".length()));
	}

	private static String host(URI jdbc) {
		return jdbc.getHost() == null || jdbc.getHost().isBlank() ? "localhost" : jdbc.getHost();
	}
}