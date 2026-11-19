package com.socialcoisa.rede.studypairs.domain;

import java.time.Instant;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Um par de estudo. Nasce PENDING (proposta) e só vira ACTIVE quando os dois membros aceitam, cada
 * um com o quebra-gelo respondido. Recusa ou prazo vencido encerra a proposta: quem não recusou
 * volta para a fila na posição original (o pedido mantém o createdAt).
 *
 * <p>O quebra-gelo de cada lado vive num JSON simples {@code {"perguntaId": "resposta", ...}}:
 * as perguntas em si ficam no catálogo (tabela study_pair_icebreakers), não aqui.
 */
public record StudyPair(String id, String userA, String userB, String goalKey, String goalText, String topic,
		Status status, Instant startedAt, Instant endedAt, String icebreakerA, String icebreakerB,
		Instant acceptedA, Instant acceptedB, Instant proposedAt, Instant deadline) {

	public enum Status { PENDING, ACTIVE, ENDED }

	private static final ObjectMapper JSON = new ObjectMapper();

	public static StudyPair pending(String id, String userA, String userB, String goalKey, String goalText,
			String topic, Instant now, Instant deadline) {
		return new StudyPair(id, userA, userB, goalKey, goalText, topic, Status.PENDING, now, null, null, null, null,
			null, now, deadline);
	}

	public boolean isMember(String userId) {
		return userA.equals(userId) || userB.equals(userId);
	}

	public String partnerOf(String userId) {
		return userA.equals(userId) ? userB : userA;
	}

	public boolean accepted(String userId) {
		return userA.equals(userId) ? acceptedA != null : acceptedB != null;
	}

	public String icebreakerOf(String userId) {
		return userA.equals(userId) ? icebreakerA : icebreakerB;
	}

	/** Prazo vencido é estado lógico: o encerramento é escrito pelo primeiro leitor que notar. */
	public boolean expired(Instant now) {
		return status == Status.PENDING && deadline != null && now.isAfter(deadline);
	}

	public StudyPair withIcebreaker(String userId, String answersJson) {
		return userA.equals(userId)
			? new StudyPair(id, userA, userB, goalKey, goalText, topic, status, startedAt, endedAt, answersJson,
				icebreakerB, acceptedA, acceptedB, proposedAt, deadline)
			: new StudyPair(id, userA, userB, goalKey, goalText, topic, status, startedAt, endedAt, icebreakerA,
				answersJson, acceptedA, acceptedB, proposedAt, deadline);
	}

	public StudyPair withAccepted(String userId, Instant at) {
		return userA.equals(userId)
			? new StudyPair(id, userA, userB, goalKey, goalText, topic, status, startedAt, endedAt, icebreakerA,
				icebreakerB, at, acceptedB, proposedAt, deadline)
			: new StudyPair(id, userA, userB, goalKey, goalText, topic, status, startedAt, endedAt, icebreakerA,
				icebreakerB, acceptedA, at, proposedAt, deadline);
	}

	/** Os dois aceites? O chamador grava a transição para ACTIVE. */
	public boolean fullyAccepted() {
		return acceptedA != null && acceptedB != null;
	}

	/** Desserializa o quebra-gelo de quem lê; JSON ausente ou inválido vira mapa vazio, nunca erro. */
	public Map<String, String> icebreakerAnswersOf(String userId) {
		String json = icebreakerOf(userId);
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return JSON.readValue(json, new TypeReference<Map<String, String>>() {
			});
		}
		catch (RuntimeException corrupted) {
			return Map.of();
		}
	}
}
