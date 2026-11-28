package com.socialcoisa.rede.network.domain;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Distribuição do tempo de execução das tarefas de um nó, em faixas (brief §8.2, "latência p50, p95
 * e p99").
 *
 * <p>O que a rede já tinha era {@code observedLatencyMs}, uma média móvel exponencial — que responde
 * "em média, quanto" e não "quanto é o pior 5%". Para cauda só serve a distribuição. Guardar cada
 * duração seria uma linha por tarefa concluída, sem teto: a rede que mais trabalha viraria a que mais
 * escreve. Aqui a tarefa entra numa <b>faixa</b> (buckets em potência de dois, de 1 ms a ~68 min), e o
 * percentil sai por interpolação linear dentro da faixa em que ele cai.</p>
 *
 * <p><b>Precisão é limitada pela faixa, e o resultado diz isso.</b> Entre 1 e 2 ms o erro é de 1 ms;
 * entre 4 e 8 s, de segundos. Para um SLA de p95 de "menos de 2 s" isso distingue "está em 1,6 s" de
 * "está em 3,2 s", que é a decisão que o painel precisa apoiar.</p>
 *
 * <p>Sem amostra, os percentis são {@code null} — não 0. Zero seria "todas as tarefas concluíram em
 * 0 ms", que é uma medida, e de uma rede que não executou nada.</p>
 */
public record TaskLatency(Map<Integer, Integer> samplesByUpperBoundMs, Map<Integer, Integer> retriesByAttempts) {

	/** Menor faixa: 1 ms. Tarefa abaixo disso cai nela. */
	private static final int FIRST_BUCKET_MS = 1;
	/** Maior faixa: 2^16 ms (≈ 68 min). Acima disso, tudo cai aqui — a tarefa não terminou em tempo útil. */
	private static final int MAX_BUCKET_MS = 1 << 16;

	/**
	 * {@code retriesByAttempts} é a CONTAGEM de tentativas perdidas das tarefas que foram devolvidas
	 * à fila antes de concluir (brief §8.2, "retentativas"): chave {@code 1} = concluiu na 2ª, chave
	 * {@code 3} = na 4ª.
	 *
	 * <p>Entram na distribuição de tentativas — o percentil responde "em quantas tentativas o nó
	 * costuma concluir" — e <b>não</b> na de duração: o tempo até a falha não é tempo de execução, e
	 * misturá-los esticaria a cauda com trabalho que não terminou. Tentativa é a outra pergunta, e é
	 * medida em contagem: antes a chave era uma faixa de tempo ({@code bucketOf}), e o percentil
	 * respondia "4 tentativas" para quem concluiu com 3.</p>
	 */
	public TaskLatency {
		samplesByUpperBoundMs = samplesByUpperBoundMs == null ? Map.of()
			: java.util.Collections.unmodifiableMap(new TreeMap<>(samplesByUpperBoundMs));
		retriesByAttempts = retriesByAttempts == null ? Map.of()
			: java.util.Collections.unmodifiableMap(new TreeMap<>(retriesByAttempts));
	}

	/** Distribuição de duração sozinha: nenhuma tarefa foi devolvida à fila. */
	public TaskLatency(Map<Integer, Integer> samplesByUpperBoundMs) {
		this(samplesByUpperBoundMs, Map.of());
	}

	/** Distribuição vazia: o nó não concluiu tarefa nenhuma na janela. */
	public static TaskLatency empty() {
		return new TaskLatency(Map.of());
	}

	/** Tarefas concluídas que já tinham sido devolvidas à fila ao menos uma vez. */
	public long retries() {
		return retriesByAttempts.values().stream().mapToLong(Integer::longValue).sum();
	}

	/** Percentil de TENTATIVAS até concluir; {@code null} sem tarefa que tenha sido devolvida. */
	public Long retriesP50() {
		return retriesPercentile(0.50);
	}

	public Long retriesP95() {
		return retriesPercentile(0.95);
	}

	public Long retriesP99() {
		return retriesPercentile(0.99);
	}

	/**
	 * Percentil de uma variável DISCRETA: devolve o valor medido que ocupa a posição, sem
	 * interpolação.
	 *
	 * <p>Interpolar entre 1 e 2 tentativas responderia "1,5 tentativas", que não existe — e o método
	 * da duração ({@link #percentile(double)}) interpola de propósito, porque milissegundos são
	 * contínuos. Aqui a distribuição é a contagem direta: com 45 tarefas de primeira (chave 0
	 * ausente, porque só quem precisou entra) e 5 com 3 perdidas, o p50 é 3, não uma média entre
	 * faixas.</p>
	 */
	Long retriesPercentile(double p) {
		long total = retries();
		if (total == 0 || p <= 0 || p > 1) {
			return null;
		}
		long position = (long) Math.ceil(p * total - 1e-9);
		long seen = 0;
		for (Map.Entry<Integer, Integer> contagem : new TreeMap<>(retriesByAttempts).entrySet()) {
			int attempts = contagem.getKey();
			int count = contagem.getValue();
			if (count <= 0) {
				continue;
			}
			seen += count;
			if (seen >= position) {
				return (long) attempts;
			}
		}
		return null;
	}

	/**
	 * A faixa (limite superior, em ms) em que uma duração cai: potência de dois, começando em 1 ms e
	 * saturando em {@value #MAX_BUCKET_MS}. A mesma função é usada na escrita e na leitura, para a
	 * faixa gravada e a faixa consultada nunca discordarem.
	 */
	public static int bucketOf(long durationMs) {
		if (durationMs <= FIRST_BUCKET_MS) {
			return FIRST_BUCKET_MS;
		}
		if (durationMs >= MAX_BUCKET_MS) {
			return MAX_BUCKET_MS;
		}
		int bucket = FIRST_BUCKET_MS;
		while (bucket < durationMs) {
			bucket <<= 1;
		}
		return bucket;
	}

	public long total() {
		return samplesByUpperBoundMs.values().stream().mapToLong(Integer::longValue).sum();
	}

	/**
	 * Percentil ({@code 0.5}, {@code 0.95}, {@code 0.99}) por interpolação linear dentro da faixa.
	 *
	 * <p>A posição do percentil (1-indexada) é {@code ceil(p * total)}; percorrendo as faixas em
	 * ordem, a faixa que contém essa posição é interpolada entre o seu limite inferior e superior,
	 * proporcionalmente a quanto do percentil já foi ultrapassado dentro dela. É o mesmo método do
	 * {@code histogram_quantile} do Prometheus, e interpolar (em vez de devolver o limite superior)
	 * evita o viés sistemático para cima.</p>
	 *
	 * <p>O limite INFERIOR é a maior faixa não vazia <b>abaixo</b> desta — não o limite superior da
	 * anterior quando a anterior está vazia. Com um único bucket ocupado o inferior é 0 (a amostra
	 * pode estar em qualquer ponto dele); se a faixa anterior tem amostras, o limite dela é o piso
	 * real, porque o percentil já a ultrapassou. Usar o limite da anterior vazia afirmaria que a
	 * amostra está acima dele, o que ninguém mediu — e sem isso uma única amostra de 1 ms responderia
	 * "128 ms".</p>
	 */
	public Long percentile(double p) {
		long total = total();
		if (total == 0 || p <= 0 || p > 1) {
			return null;
		}
		// O `ceil` sobre ponto flutuante erra na fronteira: 0.99 * 100 é 99.00000000000001, e a
		// posição sairia 100 (o máximo) em vez de 99. Um trilhonésimo de folga absorve o erro de
		// representação sem mover nenhuma posição real.
		long position = (long) Math.ceil(p * total - 1e-9);
		long seen = 0;
		// Limite inferior: o teto da última faixa COM amostra já ultrapassada; 0 se nenhuma.
		long lower = 0;
		SortedMap<Integer, Integer> ordered = new TreeMap<>(samplesByUpperBoundMs);
		for (Map.Entry<Integer, Integer> bucket : ordered.entrySet()) {
			int upper = bucket.getKey();
			int count = bucket.getValue();
			if (count <= 0) {
				continue;
			}
			if (seen + count >= position) {
				double inside = (double) (position - seen) / count;
				double estimate = lower + inside * (upper - lower);
				return Math.max(0, Math.round(estimate));
			}
			seen += count;
			lower = upper;
		}
		// Inalcançável com total > 0, mas não se inventa medida se a soma das faixas não fechar.
		return null;
	}

	public Long p50Ms() {
		return percentile(0.50);
	}

	public Long p95Ms() {
		return percentile(0.95);
	}

	public Long p99Ms() {
		return percentile(0.99);
	}
}