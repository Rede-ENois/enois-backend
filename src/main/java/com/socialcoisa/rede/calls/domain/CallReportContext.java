package com.socialcoisa.rede.calls.domain;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

/**
 * O que a denúncia de uma chamada mostra para a moderação, gravado no momento em que ela é feita.
 *
 * <p>A chamada acaba e a sala não existe mais: sem esta fotografia do instante, uma denúncia de
 * horas depois seria só "fulano disse algo naquela chamada". Por isso ficam aqui quem foi
 * denunciado, quem estava dentro (lido do servidor, nunca informado pelo cliente) e a referência
 * ao print do momento.</p>
 */
public record CallReportContext(String reportId, String callId, String reportedUserId, List<String> participants,
		String mediaId, Instant reportedAt) {

	private static final ObjectMapper JSON = new ObjectMapper();

	public static CallReportContext of(String reportId, String callId, String reportedUserId,
			List<String> participants, String mediaId, Instant reportedAt) {
		return new CallReportContext(reportId, callId, reportedUserId, List.copyOf(participants), mediaId, reportedAt);
	}

	/** Lista como texto JSON: a ordem é a do servidor e o painel não devolve o array inteiro. */
	public String participantsJson() {
		return JSON.writeValueAsString(participants);
	}

	public static List<String> participantsOf(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		return List.of(JSON.readValue(json, String[].class));
	}
}
