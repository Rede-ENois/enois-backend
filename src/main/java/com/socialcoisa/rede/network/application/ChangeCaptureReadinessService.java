package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Prontidão do banco para captura de alterações por <b>binlog</b> (doc 11 §4, brief §4).
 *
 * <p><b>O que este serviço é e o que não é.</b> Ele <b>não</b> lê o binlog. Ler exige ligar
 * {@code log_bin}, escolher {@code binlog_format = ROW}, criar um usuário com
 * {@code REPLICATION SLAVE}/{@code REPLICATION CLIENT} e guardar a posição — quatro decisões de
 * infraestrutura que o diagnóstico da fase 0 deixou explicitamente para o mantenedor. Fingir um
 * leitor sem essas condições daria um segundo mecanismo de sincronização, sem credencial e sem
 * posição, que pareceria funcionar e perderia eventos em silêncio.</p>
 *
 * <p>O que ele faz é o que faltava para <b>tomar</b> aquela decisão: medir, contra o banco em que a
 * aplicação está rodando, se o binlog está ligado, em qual formato, e se o servidor aceita um
 * consumidor. É a diferença entre "precisa ligar binlog" escrito num documento e o valor real de
 * {@code @@log_bin} no servidor de hoje.</p>
 *
 * <p><b>Nunca devolve "pronto" sem medir.</b> {@code readiness} é {@code null} quando não foi
 * possível consultar (driver que recusa o {@code select}, permissão faltando) — nulo é "não sei",
 * e é diferente de "não está pronto".</p>
 */
@Service
public class ChangeCaptureReadinessService {

	/** O que o servidor respondeu sobre a captura, ou {@code null} se não foi possível perguntar. */
	public record Readiness(Boolean ready, String reason, String logBin, String binlogFormat,
			String binlogRowImage, Long maxSizeBytes, String serverVersion, Instant checkedAt) {

		/** Não perguntado: sem conexão utilizável ou sem permissão de leitura das variáveis. */
		public static Readiness unknown(String reason, Instant at) {
			return new Readiness(null, reason, null, null, null, null, null, at);
		}
	}

	private final JdbcTemplate jdbc;
	private final Clock clock;

	/**
	 * Quando {@code true}, a instalação já decidiu usar captura por binlog e a ausência dela é
	 * erro de configuração. Quando {@code false} (padrão), a ausência é só o estado atual: o
	 * change feed escrito pela aplicação continua sendo o mecanismo em uso.
	 */
	private final boolean required;

	ChangeCaptureReadinessService(JdbcTemplate jdbc, Clock clock,
			@Value("${rede.network.change-capture.required:false}") boolean required) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.required = required;
	}

	/** A instalação exige captura por binlog? Vem de {@code rede.network.change-capture.required}. */
	public boolean required() {
		return required;
	}

	/**
	 * Consulta o servidor. As variáveis são lidas numa consulta só para que todas venham do mesmo
	 * instante — em três consultas, um reinício entre elas descreveria dois servidores.
	 */
	public Readiness check() {
		Instant now = clock.instant();
		try {
			var row = jdbc.queryForMap("select @@log_bin as log_bin, @@binlog_format as binlog_format, "
				+ "@@binlog_row_image as binlog_row_image, @@max_binlog_size as max_binlog_size, "
				+ "@@version as version");
			String logBin = String.valueOf(row.get("log_bin"));
			String format = String.valueOf(row.get("binlog_format"));
			String rowImage = String.valueOf(row.get("binlog_row_image"));
			Long maxSize = asLong(row.get("max_binlog_size"));
			String version = String.valueOf(row.get("version"));

			boolean on = "1".equals(logBin) || "ON".equalsIgnoreCase(logBin) || "TRUE".equalsIgnoreCase(logBin);
			if (!on) {
				return new Readiness(false,
					"binlog desligado (log_bin=" + logBin + "): nenhuma alteração é gravada para captura",
					logBin, format, rowImage, maxSize, version, now);
			}
			if (!"ROW".equalsIgnoreCase(format)) {
				// em STATEMENT o binlog guarda o comando, não a linha: o resultado depende do estado
				// do servidor naquele instante, e duas réplicas divergem.
				return new Readiness(false,
					"binlog_format=" + format + ": a captura precisa de ROW, não do comando",
					logBin, format, rowImage, maxSize, version, now);
			}
			return new Readiness(true, "binlog em ROW", logBin, format, rowImage, maxSize, version, now);
		}
		catch (RuntimeException noAccess) {
			// H2 na suíte, usuário sem permissão, ou servidor que não expõe as variáveis: não é
			// "desligado", é "não perguntado". Dizer desligado aqui marcaria todo ambiente de teste
			// como quebrado e esconderia a diferença que o operador precisa ver.
			return Readiness.unknown("nao foi possivel consultar as variaveis de binlog: "
				+ noAccess.getClass().getSimpleName(), now);
		}
	}

	private static Long asLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? null : Long.valueOf(String.valueOf(value));
		}
		catch (NumberFormatException notNumber) {
			return null;
		}
	}

}