package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.socialcoisa.rede.network.domain.NodeAvailability;

/**
 * O contador diário de disponibilidade do nó (V369).
 *
 * <p>É um acumulador, não uma amostra: cada heartbeat acrescenta segundos ao dia em curso, e a mesma
 * linha é somada de novo — por isso a escrita é um {@code upsert} que <b>soma</b>, nunca um insert que
 * duplica. A chave (nó, dia) garante que dois Control Planes não abram duas linhas para o mesmo dia.</p>
 */
public interface NodeUptimeRepository {

	/**
	 * Soma um trecho medido ao dia. O chamador conta cada intervalo <b>uma vez</b> (entre dois
	 * heartbeats consecutivos); repetir o mesmo intervalo contaria o tempo duas vezes — e é por isso
	 * que o serviço confere {@link #lastContact} antes de somar.
	 *
	 * @param firstSeenAt o contato em que o trecho começou
	 * @param lastSeenAt  o contato que fechou o trecho
	 * @return {@code true} quando a linha foi criada ou somada
	 */
	boolean add(String nodeId, LocalDate day, long presentSeconds, long observedSeconds, Instant firstSeenAt,
			Instant lastSeenAt);

	/**
	 * O fim do último trecho <b>recebido</b> no dia (a chegada, não o maior instante), ou
	 * {@code null} quando o dia ainda não tem linha.
	 */
	Instant lastContact(String nodeId, LocalDate day);

	/** Os dias deste nó até {@code day} (inclusive), do mais antigo para o mais novo. */
	List<NodeAvailability.Day> days(String nodeId, LocalDate day);

	/** Apaga os dias anteriores a {@code before} — a retenção. Devolve quantas linhas saíram. */
	int purgeBefore(LocalDate before);
}