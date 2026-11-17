package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O estado vivo da mesa: a ordem de fala, de quem é a vez e a cena que todos veem.
 *
 * <p>Diferente do chat e das rolagens, que são histórico (só acrescentam), o estado é REESCRITO a
 * cada clique — por isso vive sozinho, um por mesa (o id é o próprio id da mesa). Quem manda no
 * estado é o mestre; os jogadores veem e têm a vez quando chega a vez deles.</p>
 */
public class TableState {

	/** Teto da ordem de fala: mesa de RPG tem gente, não multidão. */
	public static final int MAX_TURN_ORDER = 20;
	/** Teto da cena visível: o detalhe longo do mestre mora no chat, não aqui. */
	public static final int MAX_SCENE = 600;

	private final String id;
	private final String tableId;
	private final String campaignId;
	private final List<String> turnOrder;
	private final String activeTurnUserId;
	private final Instant turnStartedAt;
	private final String scene;
	private final Instant updatedAt;

	private TableState(String id, String tableId, String campaignId, List<String> turnOrder,
			String activeTurnUserId, Instant turnStartedAt, String scene, Instant updatedAt) {
		this.id = id;
		this.tableId = tableId;
		this.campaignId = campaignId;
		this.turnOrder = turnOrder;
		this.activeTurnUserId = activeTurnUserId;
		this.turnStartedAt = turnStartedAt;
		this.scene = scene;
		this.updatedAt = updatedAt;
	}

	/** Estado vazio: a mesa nasce sem ordem, sem vez e sem cena — o jogo define depois. */
	public static TableState empty(String tableId, String campaignId, Instant now) {
		return new TableState(tableId, tableId, campaignId, List.of(), null, null, null, now);
	}

	public static TableState restore(String id, String tableId, String campaignId, List<String> turnOrder,
			String activeTurnUserId, Instant turnStartedAt, String scene, Instant updatedAt) {
		return new TableState(id, tableId, campaignId, List.copyOf(turnOrder), activeTurnUserId, turnStartedAt,
			scene, updatedAt);
	}

	/** Grava a ordem de fala. A ordem não precisa existir (nulo limpa), mas quem entra não repete. */
	public TableState withTurnOrder(List<String> order, Instant now) {
		List<String> clean = order == null ? List.of() : order.stream().distinct().toList();
		if (clean.size() > MAX_TURN_ORDER) {
			throw new ApiException(400, "TURN_ORDER_TOO_LONG",
				"A ordem de fala vai ate " + MAX_TURN_ORDER + " pessoas.");
		}
		// Se quem tem a vez saiu da ordem, a vez volta para a mesa (ninguém fala fora da fila).
		String active = clean.contains(activeTurnUserId) ? activeTurnUserId : null;
		return new TableState(id, tableId, campaignId, clean, active, active == null ? null : turnStartedAt, scene,
			now);
	}

	/** Passa a vez para alguém da ordem. Sem ordem, qualquer participante pode receber a vez. */
	public TableState withTurn(String userId, Instant now) {
		if (!turnOrder.isEmpty() && !turnOrder.contains(userId)) {
			throw new ApiException(409, "NOT_IN_TURN_ORDER",
				"Esta pessoa nao esta na ordem de fala da mesa.");
		}
		return new TableState(id, tableId, campaignId, turnOrder, userId, now, scene, now);
	}

	/** Limpa a vez: o turno acabou sem próximo definido. */
	public TableState withTurnCleared(Instant now) {
		return new TableState(id, tableId, campaignId, turnOrder, null, null, scene, now);
	}

	/** Cena visível por todos. Nulo limpa; o texto é aparado antes do teto. */
	public TableState withScene(String scene, Instant now) {
		String clean = scene == null ? null : scene.trim();
		if (clean != null && clean.length() > MAX_SCENE) {
			throw new ApiException(400, "SCENE_TOO_LONG",
				"A cena vai ate " + MAX_SCENE + " caracteres.");
		}
		if (clean != null && clean.isEmpty()) {
			clean = null;
		}
		return new TableState(id, tableId, campaignId, turnOrder, activeTurnUserId, turnStartedAt, clean, now);
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

	public List<String> turnOrder() {
		return turnOrder;
	}

	public String activeTurnUserId() {
		return activeTurnUserId;
	}

	public Instant turnStartedAt() {
		return turnStartedAt;
	}

	public String scene() {
		return scene;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

	/** Cópia para persistência sem expor a lista interna. */
	public List<String> turnOrderCopy() {
		return new ArrayList<>(turnOrder);
	}
}
