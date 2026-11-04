package com.socialcoisa.rede.calls.domain;

/**
 * Como a chamada se organiza: entre duas pessoas (toca para a outra) ou em grupo
 * ("Tamo junto": quem está no grupo vê o aviso e entra quando quiser, sem toque).
 */
public enum CallModality {
	DIRECT,
	GROUP;

	public static CallModality parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("modalidade de chamada obrigatoria");
		}
		return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
			case "DIRECT" -> DIRECT;
			case "GROUP" -> GROUP;
			default -> throw new IllegalArgumentException("modalidade de chamada desconhecida");
		};
	}
}
