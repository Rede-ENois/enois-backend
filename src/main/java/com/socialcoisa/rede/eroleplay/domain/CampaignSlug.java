package com.socialcoisa.rede.eroleplay.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * O endereço da campanha a partir do nome.
 *
 * <p>Acento vira letra (é → e) em vez de sumir: sem isso "Crônicas & Cia" viraria "cronicas-cia",
 * que não é o nome de ninguém. O resultado é sempre minúsculo, com hífen no lugar do que não é
 * letra ou número, e nunca vazio — nome só de símbolos cai em "campanha". O teto é o da coluna
 * (80), cortado sem deixar hífen pendurado.</p>
 */
public final class CampaignSlug {

	private static final int MAX_LENGTH = 80;
	private static final String FALLBACK = "campanha";

	/**
	 * Palavras que são rotas fixas em {@code /roleplay/campaigns/{slugOrId}}: uma campanha com
	 * esse endereço ficaria escondida atrás da rota.
	 */
	private static final Set<String> RESERVED = Set.of("mine");

	private CampaignSlug() {
	}

	/** O endereço colide com uma rota fixa e precisa de sufixo. */
	public static boolean isReserved(String slug) {
		return RESERVED.contains(slug);
	}

	public static String from(String name) {
		String slug = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.isEmpty()) {
			return FALLBACK;
		}
		if (slug.length() > MAX_LENGTH) {
			slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
		}
		return slug;
	}
}
