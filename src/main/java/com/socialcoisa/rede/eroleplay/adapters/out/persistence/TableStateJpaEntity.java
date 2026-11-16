package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.socialcoisa.rede.eroleplay.domain.TableState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.databind.ObjectMapper;

/**
 * O estado vivo da mesa, uma linha por mesa (o id É o id da mesa). A ordem de fala vai em JSON:
 * lista de ids curta (teto 20), lida e regravada inteira a cada mudança — não há consulta por
 * posição na fila.
 */
@Entity
@Table(name = "roleplay_table_states")
class TableStateJpaEntity {

	@Id
	private String id;

	@Column(name = "table_id", nullable = false)
	private String tableId;

	@Column(name = "campaign_id", nullable = false)
	private String campaignId;

	// A coluna da V297 é turn_order (snake_case, como as vizinhas); sem o name explícito o
	// Hibernate deriva turnOrder do campo e a validação de schema procura a coluna errada —
	// o boot morre com "missing column [turnOrder]" e derruba o deploy inteiro.
	@Column(name = "turn_order")
	private String turnOrder;

	@Column(name = "active_turn_user_id")
	private String activeTurnUserId;

	@Column(name = "turn_started_at")
	private Instant turnStartedAt;

	@Column(length = 600)
	private String scene;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected TableStateJpaEntity() {
	}

	static TableStateJpaEntity from(TableState state, ObjectMapper objectMapper) {
		TableStateJpaEntity entity = new TableStateJpaEntity();
		entity.id = state.id();
		entity.tableId = state.tableId();
		entity.campaignId = state.campaignId();
		entity.turnOrder = toJson(state.turnOrderCopy(), objectMapper);
		entity.activeTurnUserId = state.activeTurnUserId();
		entity.turnStartedAt = state.turnStartedAt();
		entity.scene = state.scene();
		entity.updatedAt = state.updatedAt();
		return entity;
	}

	TableState toDomain(ObjectMapper objectMapper) {
		List<String> order = fromJson(turnOrder, objectMapper);
		return TableState.restore(id, tableId, campaignId, order == null ? List.of() : order, activeTurnUserId,
			turnStartedAt, scene, updatedAt);
	}

	private static String toJson(List<String> order, ObjectMapper objectMapper) {
		if (order == null || order.isEmpty()) {
			return null;
		}
		return objectMapper.writeValueAsString(order);
	}

	private static List<String> fromJson(String json, ObjectMapper objectMapper) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		return List.of(objectMapper.readValue(json, String[].class));
	}
}
