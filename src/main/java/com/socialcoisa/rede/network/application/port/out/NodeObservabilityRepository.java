package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Amostras curtas e logs que alimentam o painel **por nó** (janela recente, não retenção).
 *
 * <p>Os dois vivem aqui por serem o mesmo problema: dado que o nó empurra e a tela lê logo depois.
 * O teto por nó é aplicado na escrita ({@code trim*}), não por job: um nó que some não deixa lixo, e
 * um que volta encontra o histórico recente.</p>
 */
public interface NodeObservabilityRepository {

	/**
	 * Um ponto do gráfico: um por minuto, por nó. {@code threads} é o número de threads do processo
	 * do runtime no instante do heartbeat (brief §8.2, "Runtime"): nulo é "não medido", e nunca 0.
	 * {@code swapUsedMb} e {@code memoryPressurePercent} são swap em uso e a espera por memória do
	 * PSI (brief §8.2, "Sistema operacional"), nulos quando o kernel não expõe. {@code netInMbps} e
	 * {@code netOutMbps} são a TAXA de rede em MiB/s (brief §8.2, "Throughput de entrada e saída"),
	 * nulos sem medição — não são o acumulado que o heartbeat manda para o painel do participante.
	 */
	record Sample(String nodeId, Instant sampledAt, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks, Integer queueDepth, String mode, String activity, Double diskIops,
			Double diskLatencyMs, java.math.BigDecimal temperatureC, Integer threads, Long swapUsedMb,
			Double memoryPressurePercent, Double netInMbps, Double netOutMbps) {

		/** Amostra de runtime anterior à V391: sem a taxa de rede medida. */
		public Sample(String nodeId, Instant sampledAt, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
				Integer runningTasks, Integer queueDepth, String mode, String activity, Double diskIops,
				Double diskLatencyMs, java.math.BigDecimal temperatureC, Integer threads, Long swapUsedMb,
				Double memoryPressurePercent) {
			this(nodeId, sampledAt, cpuPercent, memoryMb, diskFreeMb, runningTasks, queueDepth, mode, activity,
				diskIops, diskLatencyMs, temperatureC, threads, swapUsedMb, memoryPressurePercent, null, null);
		}

		/** Amostra de runtime anterior à V389: sem swap nem pressão de memória medidos. */
		public Sample(String nodeId, Instant sampledAt, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
				Integer runningTasks, Integer queueDepth, String mode, String activity, Double diskIops,
				Double diskLatencyMs, java.math.BigDecimal temperatureC, Integer threads) {
			this(nodeId, sampledAt, cpuPercent, memoryMb, diskFreeMb, runningTasks, queueDepth, mode, activity,
				diskIops, diskLatencyMs, temperatureC, threads, null, null, null, null);
		}

		/** Amostra de runtime anterior à V380: sem o I/O de disco medido. */
		public Sample(String nodeId, Instant sampledAt, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
				Integer runningTasks, Integer queueDepth, String mode, String activity) {
			this(nodeId, sampledAt, cpuPercent, memoryMb, diskFreeMb, runningTasks, queueDepth, mode, activity, null,
				null, null, null, null, null, null, null);
		}

		/** Amostra de runtime anterior à V382: sem a temperatura da máquina. */
		public Sample(String nodeId, Instant sampledAt, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
				Integer runningTasks, Integer queueDepth, String mode, String activity, Double diskIops,
				Double diskLatencyMs) {
			this(nodeId, sampledAt, cpuPercent, memoryMb, diskFreeMb, runningTasks, queueDepth, mode, activity,
				diskIops, diskLatencyMs, null, null, null, null, null, null);
		}
	}

	/** Uma linha do console do nó. */
	record LogLine(String id, String nodeId, Instant loggedAt, String level, String message) {
	}

	/** Grava o ponto do minuto; o mesmo minuto é substituído, não duplicado. */
	void saveSample(Sample sample);

	/**
	 * Mantém só os {@code keep} pontos mais recentes deste nó. {@code keep} vem da política do
	 * serviço, e não de um número solto no meio do SQL.
	 */
	void trimSamples(String nodeId, int keep);

	/** Série do nó, mais antiga primeiro — é a ordem em que o gráfico desenha. */
	List<Sample> samples(String nodeId, int limit);

	void appendLog(LogLine line);

	void trimLogs(String nodeId, int keep);

	/** Console do nó, mais nova primeiro — é a ordem em que o terminal mostra. */
	List<LogLine> logs(String nodeId, int limit);

	/**
	 * Soma uma tarefa concluída na faixa de duração da hora de {@code at} (brief §8.2). A soma é
	 * feita pelo banco ({@code samples = samples + 1}): dois desfechos simultâneos não se perdem.
	 */
	void addLatencySample(String nodeId, Instant at, int bucketUpperMs);

	/**
	 * Soma, para uma tarefa que concluiu, quantas tentativas foram perdidas antes de concluir (brief
	 * §8.2, "retentativas").
	 *
	 * <p>{@code retries} é uma CONTAGEM, não uma duração: vai para a faixa da hora de {@code at}
	 * somando no banco como {@link #addLatencySample}, e é lida como o próprio número. O parâmetro se
	 * chamava {@code durationMs} e era bucketeado como se fosse ms — 3 tentativas viravam 4 e acima
	 * de 16 saturavam.</p>
	 */
	void addRetrySample(String nodeId, Instant at, long retries);

	/**
	 * Contagem por faixa deste nó nas últimas {@code hours} horas, até {@code now}. Faixa sem tarefa
	 * não aparece: o que não foi medido não é zero.
	 */
	java.util.Map<Integer, Integer> latencySamples(String nodeId, Instant now, int hours);

	/** Mesma leitura de {@link #latencySamples}, na coluna das tentativas até concluir. */
	java.util.Map<Integer, Integer> retrySamples(String nodeId, Instant now, int hours);
}