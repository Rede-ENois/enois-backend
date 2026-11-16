package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Um token na mesa: um personagem, um monstro, uma marca no mapa.
 *
 * <p>A posição é em células do grid (inteiro, sem fração) — a régua de metros é do sistema de
 * regras, e o motor genérico não assume nenhuma. Token com dono é de personagem: o dono move.
 * Token sem dono é do cenário (porta, baú, monstro): só o mestre move.</p>
 */
public class TableToken {

	public static final int LABEL_MAX = 60;
	/** Grid finito: a mesa não é o infinito, e o inteiro tem de caber na coluna. */
	public static final int COORD_MIN = -9999;
	public static final int COORD_MAX = 9999;

	private final String id;
	private final String tableId;
	private final String campaignId;
	private final String ownerId;
	private final String label;
	private final String color;
	private final int posX;
	private final int posY;
	private final Instant createdAt;
	private final Instant updatedAt;

	private TableToken(String id, String tableId, String campaignId, String ownerId, String label, String color,
			int posX, int posY, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.tableId = tableId;
		this.campaignId = campaignId;
		this.ownerId = ownerId;
		this.label = label;
		this.color = color;
		this.posX = posX;
		this.posY = posY;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static TableToken create(String id, String tableId, String campaignId, String ownerId, String label,
			String color, int posX, int posY, Instant now) {
		String clean = label == null ? "" : label.trim();
		if (clean.isEmpty()) {
			throw new ApiException(400, "TOKEN_LABEL_REQUIRED", "O token precisa de um nome.");
		}
		if (clean.length() > LABEL_MAX) {
			throw new ApiException(400, "TOKEN_LABEL_TOO_LONG",
				"O nome do token vai ate " + LABEL_MAX + " caracteres.");
		}
		if (!validColor(color)) {
			throw new ApiException(400, "TOKEN_COLOR_INVALID", "Escolha uma cor em hexadecimal para o token.");
		}
		return new TableToken(id, tableId, campaignId, ownerId, clean, color, coordinate(posX), coordinate(posY), now,
			now);
	}

	public static TableToken restore(String id, String tableId, String campaignId, String ownerId, String label,
			String color, int posX, int posY, Instant createdAt, Instant updatedAt) {
		return new TableToken(id, tableId, campaignId, ownerId, label, color, posX, posY, createdAt, updatedAt);
	}

	/** Move para a nova célula. Quem pode mover já foi decidido antes de chegar aqui. */
	public TableToken moveTo(int x, int y, Instant now) {
		return new TableToken(id, tableId, campaignId, ownerId, label, color, coordinate(x), coordinate(y), createdAt,
			now);
	}

	public boolean isOwned() {
		return ownerId != null;
	}

	/** Token de personagem: move o dono (ou o mestre, decidido no serviço). Cenário: só mestre. */
	public boolean canBeMovedBy(String userId, boolean userIsMaster) {
		return userIsMaster || (ownerId != null && ownerId.equals(userId));
	}

	public String id() {
		return id;
	}

	public String tableId() {
		return tableId;
	}

	public String campaignId() {
		return campaignId;
	}

	public String ownerId() {
		return ownerId;
	}

	public String label() {
		return label;
	}

	public String color() {
		return color;
	}

	public int posX() {
		return posX;
	}

	public int posY() {
		return posY;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	private static int coordinate(int value) {
		if (value < COORD_MIN || value > COORD_MAX) {
			throw new ApiException(400, "TOKEN_POSITION_INVALID",
				"A posicao vai de " + COORD_MIN + " a " + COORD_MAX + " celulas.");
		}
		return value;
	}

	/** Hex do tema (`RRGGBB` ou `RRGGBBAA`) — a renderização é do front, a validação é daqui. */
	private static boolean validColor(String color) {
		if (color == null) {
			return false;
		}
		String hex = color.startsWith("#") ? color.substring(1) : color;
		if (hex.length() != 6 && hex.length() != 8) {
			return false;
		}
		return hex.chars().allMatch(c -> Character.isDigit(c)
			|| (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
	}
}
