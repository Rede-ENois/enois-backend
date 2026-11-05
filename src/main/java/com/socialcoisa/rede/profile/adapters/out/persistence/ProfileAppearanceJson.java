package com.socialcoisa.rede.profile.adapters.out.persistence;

import com.socialcoisa.rede.profile.domain.ProfileAppearance;
import com.socialcoisa.rede.profile.domain.ProfileLook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tradução entre o documento guardado na coluna e o valor de domínio.
 *
 * Só campos conhecidos são lidos: documento com chave a mais (versão futura, linha editada
 * à mão) não quebra a leitura do perfil, e documento corrompido cai no padrão da plataforma
 * em vez de derrubar a página. Escrever vazio vira {@code null} — "nunca escolheu" precisa
 * continuar sendo a ausência, não um objeto de nulos ocupando a coluna.
 */
final class ProfileAppearanceJson {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ProfileAppearanceJson() {
	}

	static String encode(ProfileAppearance appearance) {
		if (appearance == null || appearance.isEmpty()) {
			return null;
		}
		return MAPPER.writeValueAsString(appearance);
	}

	static ProfileAppearance decode(String json) {
		if (json == null || json.isBlank()) {
			return ProfileAppearance.empty();
		}
		try {
			JsonNode root = MAPPER.readTree(json);
			if (root == null || !root.isObject()) {
				return ProfileAppearance.empty();
			}
			return new ProfileAppearance(look(root.path("social")), look(root.path("professional")));
		} catch (RuntimeException e) {
			return ProfileAppearance.empty();
		}
	}

	private static ProfileLook look(JsonNode node) {
		if (node == null || !node.isObject()) {
			return ProfileLook.empty();
		}
		return new ProfileLook(text(node, "theme"), text(node, "accent"), text(node, "font"), text(node, "header"),
			bool(node, "textures"), text(node, "borders"), integer(node, "decoration"));
	}

	private static String text(JsonNode node, String key) {
		JsonNode value = node.path(key);
		return value.isString() ? value.asString() : null;
	}

	private static Boolean bool(JsonNode node, String key) {
		JsonNode value = node.path(key);
		return value.isBoolean() ? value.asBoolean() : null;
	}

	private static Integer integer(JsonNode node, String key) {
		JsonNode value = node.path(key);
		return value.isIntegralNumber() ? value.asInt() : null;
	}
}
