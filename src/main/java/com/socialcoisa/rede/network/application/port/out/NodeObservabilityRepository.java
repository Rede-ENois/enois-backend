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

	/** Um ponto do gráfico: um por minuto, por nó. */
	record Sample(String nodeId, Instant sampledAt, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
			Integer runningTasks, Integer queueDepth, String mode, String activity, Double diskIops,
			Double diskLatencyMs) {

		/** Amostra de runtime anterior à V380: sem o I/O de disco medido. */
		public Sample(String nodeId, Instant sampledAt, Double cpuPercent, Integer memoryMb, Long diskFreeMb,
				Integer runningTasks, Integer queueDepth, String mode, String activity) {
			this(nodeId, sampledAt, cpuPercent, memoryMb, diskFreeMb, runningTasks, queueDepth, mode, activity, null,
				null);
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
}