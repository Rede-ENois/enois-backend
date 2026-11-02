package com.socialcoisa.rede.mentoring.domain;

/**
 * Como a sala de uma sessão é escolhida.
 *
 * <p>{@link #JITSI_SESSION} é o padrão e não exige configuração nenhuma do mentor: cada sessão ganha
 * uma sala Jitsi própria. {@link #JITSI_PERMANENT} usa uma sala fixa do mentor (o mesmo link em
 * todas as sessões, útil para divulgar). {@link #CUSTOM} aponta para uma sala externa — Meet, Zoom,
 * o que o mentor já usa.
 */
public enum RoomMode {

	JITSI_SESSION,

	JITSI_PERMANENT,

	CUSTOM;

	/** Modo gravado no banco; valor desconhecido (linha antiga ou escrita manual) volta ao padrão. */
	public static RoomMode fromStored(String raw) {
		if (raw == null || raw.isBlank()) {
			return JITSI_SESSION;
		}
		for (RoomMode mode : values()) {
			if (mode.name().equalsIgnoreCase(raw.trim())) {
				return mode;
			}
		}
		return JITSI_SESSION;
	}

}
