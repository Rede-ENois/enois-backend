package com.socialcoisa.rede.profile.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Um kit de emblemas: o conjunto que o perfil exibia em um momento passado.
 *
 * <p>As entradas seguem o mesmo formato de {@code users.badges} (URLs do CDN ou ids
 * {@code emblema-*}, na ordem em que eram exibidas), então aplicar o kit de volta é trocar a
 * lista. O histórico guarda referência, não arquivo.</p>
 */
public record BadgeKit(String id, List<String> badges, Instant createdAt) {

	/** Um kit novo, com id próprio: é o que entra no histórico no momento da troca. */
	public static BadgeKit create(List<String> badges, Instant createdAt) {
		return new BadgeKit("bkit_" + UUID.randomUUID().toString().replace("-", ""), badges, createdAt);
	}

	public BadgeKit {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("KIT_ID_REQUIRED");
		}
		badges = badges == null ? List.of() : List.copyOf(badges);
		if (createdAt == null) {
			throw new IllegalArgumentException("KIT_DATE_REQUIRED");
		}
	}
}
