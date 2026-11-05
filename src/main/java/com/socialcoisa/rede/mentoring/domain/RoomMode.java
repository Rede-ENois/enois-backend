package com.socialcoisa.rede.mentoring.domain;

/**
 * Como a sala de uma sessão é escolhida.
 *
 * <p>{@link #EPAPO_SESSION} é o padrão e não exige configuração nenhuma do mentor: cada sessão ganha
 * uma sala própria no Epapo. {@link #EPAPO_PERMANENT} usa uma sala fixa do mentor (o mesmo nome em
 * todas as sessões, útil para divulgar). {@link #CUSTOM} aponta para uma sala externa — Meet, Zoom,
 * o que o mentor já usa; é sala aberta, sem o controle de entrada do Epapo.
 *
 * <p>Os nomes antigos {@code JITSI_SESSION} e {@code JITSI_PERMANENT} continuam sendo lidos do
 * banco (linhas gravadas antes da troca) e respondem como os equivalentes do Epapo.
 */
public enum RoomMode {

	EPAPO_SESSION,

	EPAPO_PERMANENT,

	CUSTOM;

	/** Modo gravado no banco; valor desconhecido (linha antiga ou escrita manual) volta ao padrão. */
	public static RoomMode fromStored(String raw) {
		if (raw == null || raw.isBlank()) {
			return EPAPO_SESSION;
		}
		String value = raw.trim();
		for (RoomMode mode : values()) {
			if (mode.name().equalsIgnoreCase(value)) {
				return mode;
			}
		}
		if (value.equalsIgnoreCase("JITSI_SESSION")) {
			return EPAPO_SESSION;
		}
		if (value.equalsIgnoreCase("JITSI_PERMANENT")) {
			return EPAPO_PERMANENT;
		}
		return EPAPO_SESSION;
	}

}
