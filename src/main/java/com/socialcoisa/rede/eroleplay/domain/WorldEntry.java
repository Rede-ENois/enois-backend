package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Uma entidade do mundo da campanha: um local, um NPC, um item, uma pista, um trecho de lore.
 *
 * <p>O mundo é do GRUPO. Qualquer participante registra — a anotação de um jogador é memória de
 * mesa tão valiosa quanto a lore do mestre — e cada um edita e apaga o que escreveu; o mestre
 * pode em tudo. A vinculação com ficha (um NPC que é o personagem de alguém) é opcional, nunca
 * sai da campanha e o id dela não atravessa a API — atravessa o nome do personagem.</p>
 */
public class WorldEntry {

	/** Título curto: o world builder é atlas, não romance. */
	public static final int TITLE_MAX = 120;
	/** O corpo é longo de propósito — é a lore —, mas tem teto: 20 mil caracteres. */
	public static final int BODY_MAX = 20000;
	/** Teto de tags, contadas com a vírgula: 600 caracteres de `a,b,c`. */
	public static final int TAGS_MAX = 600;

	private final String id;
	private final String campaignId;
	private final WorldKind kind;
	private final String title;
	private final String body;
	private final List<String> tags;
	private final String linkedSheetId;
	private final String authorId;
	private final Instant createdAt;
	private final Instant updatedAt;

	private WorldEntry(String id, String campaignId, WorldKind kind, String title, String body, List<String> tags,
			String linkedSheetId, String authorId, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.kind = kind;
		this.title = title;
		this.body = body;
		this.tags = tags;
		this.linkedSheetId = linkedSheetId;
		this.authorId = authorId;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/** Entrada nova: título obrigatório, corpo e tags opcionais, vinculação de ficha válida. */
	public static WorldEntry create(String campaignId, WorldKind kind, String title, String body, List<String> tags,
			String linkedSheetId, String authorId, Instant now) {
		if (kind == null) {
			throw new ApiException(400, "WORLD_KIND_REQUIRED", "Escolha o tipo da entrada (LOCAL, NPC, ITEM, PISTA ou LORE).");
		}
		return new WorldEntry(UUID.randomUUID().toString(), campaignId, kind, title(title), body(body), tags(tags),
			linkedSheetId, authorId, now, now);
	}

	public static WorldEntry restore(String id, String campaignId, WorldKind kind, String title, String body,
			List<String> tags, String linkedSheetId, String authorId, Instant createdAt, Instant updatedAt) {
		return new WorldEntry(id, campaignId, kind, title, body, tags, linkedSheetId, authorId, createdAt, updatedAt);
	}

	/** Reescreve conteúdo. Identidade, autoria e criação não mudam; a vinculação de ficha pode sair. */
	public WorldEntry edit(WorldKind kind, String title, String body, List<String> tags, String linkedSheetId,
			Instant now) {
		if (kind == null) {
			throw new ApiException(400, "WORLD_KIND_REQUIRED", "Escolha o tipo da entrada (LOCAL, NPC, ITEM, PISTA ou LORE).");
		}
		return new WorldEntry(id, campaignId, kind, title(title), body(body), tags(tags), linkedSheetId, authorId,
			createdAt, now);
	}

	/** O mestre vê e mexe em tudo; quem escreveu, no que escreveu; o resto lê. */
	public boolean canBeEditedBy(String userId, boolean userIsMaster) {
		return userIsMaster || authorId.equals(userId);
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public WorldKind kind() {
		return kind;
	}

	public String title() {
		return title;
	}

	public String body() {
		return body;
	}

	public List<String> tags() {
		return tags;
	}

	public String linkedSheetId() {
		return linkedSheetId;
	}

	public String authorId() {
		return authorId;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	/** Cópia de leitura sem expor a lista interna. */
	public List<String> tagsCopy() {
		return new java.util.ArrayList<>(tags);
	}

	private static String title(String value) {
		String clean = value == null ? "" : value.trim();
		if (clean.isEmpty()) {
			throw new ApiException(400, "WORLD_TITLE_REQUIRED", "A entrada do mundo precisa de um título.");
		}
		if (clean.length() > TITLE_MAX) {
			throw new ApiException(400, "WORLD_TITLE_TOO_LONG",
				"O título vai até " + TITLE_MAX + " caracteres.");
		}
		return clean;
	}

	private static String body(String value) {
		if (value == null) {
			return null;
		}
		String clean = value.trim();
		if (clean.isEmpty()) {
			return null;
		}
		if (clean.length() > BODY_MAX) {
			throw new ApiException(400, "WORLD_BODY_TOO_LONG",
				"O corpo vai até " + BODY_MAX + " caracteres.");
		}
		return clean;
	}

	/** Tags vindas de texto livre (`a, b,c`) viram lista limpa, sem repetição e com teto. */
	private static List<String> tags(List<String> value) {
		if (value == null || value.isEmpty()) {
			return List.of();
		}
		List<String> clean = value.stream()
			.filter(tag -> tag != null && !tag.isBlank())
			.map(tag -> tag.trim().toLowerCase(Locale.ROOT))
			.distinct()
			.toList();
		String joined = String.join(",", clean);
		if (joined.length() > TAGS_MAX) {
			throw new ApiException(400, "WORLD_TAGS_TOO_LONG",
				"As tags somam até " + TAGS_MAX + " caracteres.");
		}
		return clean;
	}
}
