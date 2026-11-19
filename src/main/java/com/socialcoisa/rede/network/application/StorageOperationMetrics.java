package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operações por segundo e latência do Storage no painel (brief §17, V366).
 *
 * <p><b>Antes:</b> o §17 pede "Operações por segundo" e "Latência" na visão geral e o painel não tinha
 * nenhum dos dois — não por falta de tela, mas porque nada contava operação. Capacidade, uso e
 * integridade são estado que o catálogo já grava; operação é evento, e evento precisa de lugar para
 * cair. <b>Agora:</b> cada operação no catálogo de objetos grava o minuto, o verbo, o resultado e os
 * milissegundos; a leitura agrega.</p>
 *
 * <h2>O que este serviço NÃO faz</h2>
 *
 * <ul>
 * <li><b>Não guarda cada requisição.</b> Uma linha por (minuto, operação, resultado): um SDK que lista
 * milhões de vezes soma no mesmo contador. A tabela é uma série, não um log.</li>
 * <li><b>Não estima.</b> Minuto sem operação não aparece na janela; a taxa é calculada sobre o tempo
 * realmente coberto pelas linhas que existem, e o relatório diz qual é esse tempo. Inventar zeros para
 * minutos vazios daria uma taxa menor que a real e esconderia pico de tráfego no meio.</li>
 * <li><b>Não conta o gateway S3 separadamente.</b> O gateway grava, lê e apaga pelo mesmo catálogo, e o
 * registro é chamado lá — o número é o do Storage, venha o pedido do SDK ou do nó.</li>
 * </ul>
 *
 * <h2>Latência: por que soma e máximo, e não média no banco</h2>
 *
 * <p>Gravar a média por minuto e depois tirar a média das médias pesa um minuto parado igual a um minuto
 * de pico. A tabela guarda {@code total_ms} e {@code max_ms}; a média sai na leitura
 * ({@code soma_ms / operações}), e é a média verdadeira da janela inteira. O máximo é o pior caso, que
 * é o que explica reclamação de cliente com painel verde.</p>
 */
@Service
public class StorageOperationMetrics {

	private static final Logger LOG = LoggerFactory.getLogger(StorageOperationMetrics.class);

	/**
	 * Os verbos que o painel separa — e só os que alguém registra. Fora desta lista, a operação entra
	 * como {@code OTHER}.
	 *
	 * <p>Lista curta de propósito: um verbo aqui sem chamador viraria uma linha que nunca aparece no
	 * painel, e o operador leria "multipart nunca acontece" quando o que falta é a instrumentação. Cada
	 * verbo entra junto com o ponto que o conta.</p>
	 */
	static final List<String> OPERACOES = List.of("PUT", "GET", "HEAD", "DELETE", "LIST");

	/** Como a operação terminou: pedido errado ({@code CLIENT}) é diferente de falha nossa ({@code SERVER}). */
	enum Resultado {
		OK, CLIENT, SERVER
	}

	/** O agregado de uma janela, como o painel mostra. */
	public record Report(Instant from, Instant to, long seconds, long operations, double opsPerSecond,
			double averageMs, long maxMs, Map<String, Long> byOperation, Map<String, Long> byOutcome) {
	}

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final Duration retention;

	StorageOperationMetrics(JdbcTemplate jdbc, Clock clock,
			@Value("${rede.storage.metrics.retention-days:30}") long retentionDays) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.retention = Duration.ofDays(Math.max(1, retentionDays));
	}

	/**
	 * Registra uma operação. Chamado pelo catalog de objetos <b>depois</b> de a operação terminar — o
	 * tempo medido é o da operação, não o da chamada que registra.
	 *
	 * <p>Falha ao registrar não derruba a operação do usuário: a métrica é observação, e perder uma
	 * amostra não pode virar erro de upload. O aviso fica no log.</p>
	 */
	public void record(String operation, Resultado outcome, long millis) {
		String verbo = OPERACOES.contains(operation) ? operation : "OTHER";
		Instant minuto = clock.instant().truncatedTo(ChronoUnit.MINUTES);
		try {
			// UPSERT na chave: a linha do minuto já existe? soma. É o mesmo dado, agregado — e a soma é
			// atômica no banco, então duas instâncias do Control Plane no mesmo minuto não se perdem.
			// Os parâmetros repetem de propósito: `values(total_ms)` no ON DUPLICATE KEY é sintaxe
			// depreciada e não existe em todo banco de teste. Passar o valor de novo é portátil.
			jdbc.update("""
				insert into network_storage_operation_metrics (bucket_at, operation, outcome, req_count, total_ms, max_ms)
				values (?, ?, ?, 1, ?, ?)
				on duplicate key update req_count = req_count + 1, total_ms = total_ms + ?,
					max_ms = greatest(max_ms, ?)
				""", java.sql.Timestamp.from(minuto), verbo, outcome.name(), millis, millis, millis, millis);
		}
		catch (RuntimeException falhou) {
			LOG.warn("storage_metrics: nao foi possivel registrar {} {} ({} ms): {}", verbo, outcome, millis,
				falhou.getMessage());
		}
	}

	/**
	 * O relatório da janela pedida (padrão: 15 minutos).
	 *
	 * <p>{@code seconds} é a janela efetivamente medida (arredondada para baixo ao minuto, porque a
	 * tabela é por minuto). Sem nenhuma operação, {@code opsPerSecond} e {@code averageMs} são
	 * {@code 0}, {@code seconds} é {@code 0} e {@code maxMs} é {@code 0}: a tela mostra "sem
	 * operação", não uma divisão por zero.</p>
	 */
	public Report report(Duration janela) {
		Duration efetiva = janela == null || janela.isNegative() || janela.isZero() ? Duration.ofMinutes(15)
			: janela;
		Instant to = clock.instant();
		Instant from = to.minus(efetiva);
		List<Linha> linhas = jdbc.query("""
			select operation, outcome, sum(req_count) as req_count, sum(total_ms) as total_ms, max(max_ms) as max_ms
			from network_storage_operation_metrics where bucket_at >= ?
			group by operation, outcome order by operation
			""", (rs, i) -> new Linha(rs.getString("operation"), rs.getString("outcome"), rs.getLong("req_count"),
			rs.getLong("total_ms"), rs.getLong("max_ms")), java.sql.Timestamp.from(from.truncatedTo(ChronoUnit.MINUTES)));
		return aggregate(from, to, linhas);
	}

	/**
	 * A conta sobre as linhas que o banco devolveu. Estática e separada da consulta para o teste medir
	 * <b>a conta de verdade</b> sem precisar de um banco: os três jeitos de errar (média das médias,
	 * zero em vez de "sem dado", 4xx e 5xx no mesmo balde) são reprovados aqui.
	 */
	static Report aggregate(Instant from, Instant to, List<Linha> linhas) {
		long total = linhas.stream().mapToLong(Linha::reqCount).sum();
		long somaMs = linhas.stream().mapToLong(Linha::totalMs).sum();
		long piorMs = linhas.stream().mapToLong(Linha::maxMs).max().orElse(0L);
		Map<String, Long> porOperacao = new LinkedHashMap<>();
		Map<String, Long> porResultado = new LinkedHashMap<>();
		for (Linha linha : linhas) {
			porOperacao.merge(linha.operation(), linha.reqCount(), Long::sum);
			porResultado.merge(linha.outcome(), linha.reqCount(), Long::sum);
		}
		// O tempo coberto é o da janela quando há dado; sem dado, zero segundos (e taxa zero, não
		// divisão por zero nem "taxa da janela toda" fingindo que houve movimento).
		long segundos = total == 0 ? 0 : Math.max(1, effectiveSeconds(from, to));
		double porSegundo = segundos == 0 ? 0 : (double) total / segundos;
		double media = total == 0 ? 0 : (double) somaMs / total;
		return new Report(from, to, segundos, total, arredondar(porSegundo), arredondar(media), piorMs,
			porOperacao, porResultado);
	}

	/** Apaga o que passou da retenção. Chamado pela varredura; devolve quantas linhas saíram. */
	public int purge() {
		java.sql.Timestamp limite = java.sql.Timestamp.from(clock.instant().minus(retention));
		int removidas = jdbc.update("delete from network_storage_operation_metrics where bucket_at < ?",
			(Object) limite);
		if (removidas > 0) {
			LOG.info("storage_metrics: {} minuto(s) fora da retencao removido(s)", removidas);
		}
		return removidas;
	}

	private static long effectiveSeconds(Instant from, Instant to) {
		return Math.max(0, Duration.between(from, to).getSeconds());
	}

	private static double arredondar(double valor) {
		return Math.round(valor * 1000d) / 1000d;
	}

	/** Uma linha agregada do banco: o que a consulta devolve por (operação, resultado). */
	record Linha(String operation, String outcome, long reqCount, long totalMs, long maxMs) {
	}
}