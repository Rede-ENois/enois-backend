package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

import com.socialcoisa.rede.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A ficha publicada na biblioteca — a CÓPIA que o dono mandou para fora da campanha.
 *
 * <p>Publicar é decisão do dono, no momento em que ele decide (opt-in). O que sai é o recorte que
 * ele já abriu à campanha: campo privado nunca entra na cópia, então não existe caminho em que o
 * segredo da ficha vire público por publicar. A cópia é um instantâneo — atualizar a ficha não
 * reescreve a publicação; republicar é o gesto de atualizar.</p>
 *
 * <p>O id interno da ficha fica aqui dentro e não atravessa a API pública: a biblioteca responde
 * pelo id da publicação, e o nome do autor não é parte do registro (quem assina a ficha é o
 * personagem, e o perfil do dono fica no próprio perfil).</p>
 */
public class LibrarySheet {

	/** Limite do nome publicado: o mesmo teto do nome da ficha. */
	public static final int NAME_MAX = 120;

	/** Limite de campos publicados: uma ficha de mesa não precisa de mais que isso. */
	public static final int FIELDS_MAX = 200;

	/** Limite de caracteres por campo publicado. */
	public static final int FIELD_VALUE_MAX = 2000;

	private final String id;
	private final String campaignId;
	private final String sheetId;
	private final String ownerId;
	private final String characterName;
	private final String gameSystem;
	private final Map<String, String> fields;
	private final Instant publishedAt;
	private final Instant updatedAt;

	private LibrarySheet(String id, String campaignId, String sheetId, String ownerId, String characterName,
			String gameSystem, Map<String, String> fields, Instant publishedAt, Instant updatedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.sheetId = sheetId;
		this.ownerId = ownerId;
		this.characterName = characterName;
		this.gameSystem = gameSystem;
		this.fields = new LinkedHashMap<>(fields);
		this.publishedAt = publishedAt;
		this.updatedAt = updatedAt;
	}

	/**
	 * Publica o recorte público de uma ficha. O serviço entrega as entradas JÁ filtradas pela
	 * régua de visibilidade — o domínio ainda confere: campo nulo, vazio demais ou grande demais
	 * não passa, porque a biblioteca é superfície pública e recebe visitante anônimo.
	 */
	public static LibrarySheet publish(String campaignId, String sheetId, String ownerId, String characterName,
			String gameSystem, Map<String, String> publicFields, Instant now) {
		if (characterName == null || characterName.isBlank()) {
			throw new ApiException(400, "LIBRARY_NAME_REQUIRED", "O personagem publicado precisa de nome.");
		}
		if (characterName.length() > NAME_MAX) {
			throw new ApiException(400, "LIBRARY_NAME_TOO_LONG",
				"O nome do personagem passa do limite de " + NAME_MAX + " caracteres.");
		}
		if (gameSystem == null || gameSystem.isBlank()) {
			throw new ApiException(400, "LIBRARY_SYSTEM_REQUIRED",
				"A publicacao precisa do sistema de regras da campanha.");
		}
		Map<String, String> clean = new LinkedHashMap<>();
		for (Map.Entry<String, String> field : publicFields.entrySet()) {
			String key = field.getKey() == null ? "" : field.getKey().trim();
			String value = field.getValue() == null ? "" : field.getValue().trim();
			if (key.isEmpty() || value.isEmpty()) {
				continue;
			}
			if (key.length() > 80) {
				throw new ApiException(400, "LIBRARY_FIELD_TOO_LONG",
					"O campo " + key + " passa do limite de 80 caracteres.");
			}
			if (value.length() > FIELD_VALUE_MAX) {
				throw new ApiException(400, "LIBRARY_FIELD_TOO_LONG",
					"O campo " + key + " passa do limite de " + FIELD_VALUE_MAX + " caracteres.");
			}
			clean.put(key, value);
		}
		if (clean.size() > FIELDS_MAX) {
			throw new ApiException(400, "LIBRARY_FIELDS_TOO_MANY",
				"A publicacao passa do limite de " + FIELDS_MAX + " campos.");
		}
		if (clean.isEmpty()) {
			throw new ApiException(400, "LIBRARY_EMPTY",
				"Nada a publicar: nenhum campo da ficha esta aberto a campanha.");
		}
		return new LibrarySheet("roleplay_library_" + UUID.randomUUID(), campaignId, sheetId, ownerId,
			characterName.trim(), gameSystem.trim(), clean, now, now);
	}

	/** Reconstrói a publicação persistida. */
	public static LibrarySheet restore(String id, String campaignId, String sheetId, String ownerId,
			String characterName, String gameSystem, Map<String, String> fields, Instant publishedAt,
			Instant updatedAt) {
		return new LibrarySheet(id, campaignId, sheetId, ownerId, characterName, gameSystem, fields, publishedAt,
			updatedAt);
	}

	/** O id da ficha de origem: para uso INTERNO do serviço, nunca na resposta pública. */
	public String sheetId() {
		return sheetId;
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public String ownerId() {
		return ownerId;
	}

	public String characterName() {
		return characterName;
	}

	public String gameSystem() {
		return gameSystem;
	}

	public Map<String, String> fields() {
		return new LinkedHashMap<>(fields);
	}

	public Instant publishedAt() {
		return publishedAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
