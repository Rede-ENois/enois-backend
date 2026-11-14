package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.eroleplay.domain.TableSeat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roleplay_table_seats")
class TableSeatJpaEntity {

	@Id
	private String id;

	@Column(name = "table_id", nullable = false)
	private String tableId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "seated_at", nullable = false)
	private Instant seatedAt;

	protected TableSeatJpaEntity() {
	}

	static TableSeatJpaEntity fromDomain(TableSeat seat) {
		TableSeatJpaEntity entity = new TableSeatJpaEntity();
		entity.id = seat.tableId() + ":" + seat.userId();
		entity.tableId = seat.tableId();
		entity.userId = seat.userId();
		entity.seatedAt = seat.seatedAt();
		return entity;
	}

	TableSeat toDomain() {
		return TableSeat.restore(tableId, userId, seatedAt);
	}

	String tableId() {
		return tableId;
	}

	String userId() {
		return userId;
	}
}
