package com.socialcoisa.rede.shared.security;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Modo pânico: bloqueia TODA escrita da aplicação com 429 enquanto ativo.
 *
 * O estado vive na tabela {@code system_flags} (compartilhada entre os pods) e cada instância
 * mantém uma cópia em memória atualizada a cada 2s — o caminho da requisição não pode fazer uma
 * consulta ao banco para decidir, ainda mais durante um ataque. O endpoint que liga/desliga é
 * isento do próprio bloqueio; login/refresh/2FA também, senão ninguém conseguiria entrar para
 * desligar.
 */
@Service
public class PanicModeService {

	private static final Logger LOG = LoggerFactory.getLogger(PanicModeService.class);
	private static final String FLAG = "panic_mode";
	private static final long REFRESH_MS = 2_000;

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final boolean envEnabled;

	/**
	 * Trava da recarga. Quem espera por ela e somente o agendador; o caminho da requisicao tenta
	 * pegar e segue em frente — nunca fica parado atras de uma consulta ao banco.
	 */
	private final ReentrantLock refreshLock = new ReentrantLock();

	private volatile boolean active;
	private volatile long refreshedAt;

	public PanicModeService(JdbcTemplate jdbc, Clock clock,
			@org.springframework.beans.factory.annotation.Value("${rede.panic.env-enabled:}") String envValue) {
		this.jdbc = jdbc;
		this.clock = clock;
		String clean = envValue == null ? "" : envValue.trim().toLowerCase(java.util.Locale.ROOT);
		// Secret `panic: yes` liga; vazio/ausente mantém online.
		this.envEnabled = java.util.Set.of("yes", "true", "on", "1").contains(clean);
	}

	public boolean active() {
		// Env var é override duro: durante uma emergência ninguém desliga pelo painel; só mudando
		// PANIC_MODE e reiniciando o pod.
		if (envEnabled) {
			return true;
		}
		if (System.currentTimeMillis() - refreshedAt > REFRESH_MS) {
			refreshIfIdle();
		}
		return active;
	}

	/**
	 * Recarga do caminho da requisicao: tenta a trava e desiste se alguem ja esta recarregando.
	 * Com `synchronized` aqui, uma consulta lenta prendia TODAS as threads do Tomcat na mesma trava
	 * (o pool esgotado fazia cada uma esperar o timeout de conexao, ~10s, em fila) e a aplicacao nao
	 * se recuperava sozinha.
	 */
	private void refreshIfIdle() {
		if (!refreshLock.tryLock()) {
			return;
		}
		try {
			load();
		}
		finally {
			refreshLock.unlock();
		}
	}

	public boolean envLocked() {
		return envEnabled;
	}

	public void setActive(boolean value) {
		refreshLock.lock();
		try {
			writeFlag(value);
			active = value;
			refreshedAt = System.currentTimeMillis();
			LOG.warn("panic_mode changed active={}", value);
		}
		finally {
			refreshLock.unlock();
		}
	}

	private void writeFlag(boolean value) {
		Timestamp now = Timestamp.from(clock.instant());
		int updated = jdbc.update("update system_flags set flag_value = ?, updated_at = ? where name = ?",
			value ? "true" : "false", now, FLAG);
		if (updated == 0) {
			try {
				jdbc.update("insert into system_flags (name, flag_value, updated_at) values (?, ?, ?)", FLAG,
					value ? "true" : "false", now);
			}
			catch (DataAccessException race) {
				// Outro pod inseriu ao mesmo tempo: o update agora encontra a linha.
				jdbc.update("update system_flags set flag_value = ?, updated_at = ? where name = ?",
					value ? "true" : "false", now, FLAG);
			}
		}
	}

	@Scheduled(initialDelayString = "${rede.panic.refresh-initial-delay-ms:5000}",
			fixedDelayString = "${rede.panic.refresh-ms:2000}")
	public void refresh() {
		refreshLock.lock();
		try {
			load();
		}
		finally {
			refreshLock.unlock();
		}
	}

	/**
	 * Le a flag e atualiza o estado em memoria. `refreshedAt` avanca TAMBEM quando a leitura falha:
	 * sem isso, com o banco fora, toda requisicao repetia a consulta e o sistema nao se recuperava
	 * nem voltava sozinho quando o banco retornava.
	 */
	private void load() {
		try {
			List<String> values = jdbc.query("select flag_value from system_flags where name = ?",
				(rs, rowNum) -> rs.getString(1), FLAG);
			active = !values.isEmpty() && "true".equalsIgnoreCase(values.get(0));
		}
		catch (DataAccessException failure) {
			// Banco fora: mantém o último estado conhecido (se o pânico estava ligado, continua
			// bloqueando; se não estava, não vira bloqueio total por causa de um erro de banco).
			LOG.warn("panic_mode refresh_failed keeping={}", active);
		}
		finally {
			refreshedAt = System.currentTimeMillis();
		}
	}
}
