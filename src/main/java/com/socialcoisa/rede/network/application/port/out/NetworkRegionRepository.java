package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Regiões fora de operação por decisão humana (V289) — a outra metade da recuperação regional
 * (docs/rede-distribuida/06-contingencia.md §3).
 *
 * <p>A região que entra aqui SAI da rede: nó dela não recebe tarefa nova, não conta vaga, não ouve
 * {@code acceptTasks=true} e devolve à fila o que tinha na mão. A devolução é o outro lado do
 * critério do plano: "região afetada sai; nós de outras regiões atendem o que podem" — uma tarefa
 * presa a um nó da região caída esperaria o claim TTL inteiro por um resultado que não vem, porque
 * o nó continua {@code ACTIVE} e a drenagem existente olha só o {@code status} dele.</p>
 *
 * <p>A declaração é humana e auditada, com motivo obrigatório, pelo mesmo raciocínio do modo
 * operacional (ADR-009): tirar uma região do ar é decisão de incidente, não número que o Control
 * Plane meça. E é reversível por decisão humana também — a região volta quando alguém declara o
 * fim, não por relógio: a automação readmitindo região caída é o incidente voltando sem ninguém
 * ter visto.</p>
 */
public interface NetworkRegionRepository {

	/** Regiões fora de operação agora, em ordem de declaração. */
	List<String> outOfService();

	/** A declaração de uma região, se existir. */
	Optional<RegionRecord> find(String region);

	/**
	 * Declara a região fora de operação. Repetir não duplica nem reescreve a data: {@code since} é
	 * quando a região ENTROU na contingência, e reescrever apagaria a linha do tempo do incidente.
	 */
	void declare(String region, String actorId, String reason, Instant now);

	/** @return {@code true} se a região estava fora de operação e agora não está mais */
	boolean clear(String region);

	/** O registro que o painel mostra: quem declarou, por quê e desde quando. */
	record RegionRecord(String region, String actorId, String reason, Instant since) {
	}
}
