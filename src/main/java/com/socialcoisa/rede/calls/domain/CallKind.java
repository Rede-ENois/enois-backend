package com.socialcoisa.rede.calls.domain;

/** O que a chamada carrega por padrão: só voz, ou voz e vídeo. */
public enum CallKind {
	AUDIO,
	VIDEO;

	public static CallKind parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("tipo de chamada obrigatorio");
		}
		return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
			case "AUDIO" -> AUDIO;
			case "VIDEO" -> VIDEO;
			default -> throw new IllegalArgumentException("tipo de chamada desconhecido");
		};
	}
}
