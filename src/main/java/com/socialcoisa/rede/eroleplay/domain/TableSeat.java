package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

/**
 * A associação de uma pessoa a uma mesa.
 *
 * <p>Sem id próprio: a identidade é o par (mesa, pessoa). Sentar duas vezes na mesma mesa não é
 * um estado possível, e o banco garante isso com índice único.</p>
 */
public class TableSeat {

	private final String tableId;
	private final String userId;
	private final Instant seatedAt;

	private TableSeat(String tableId, String userId, Instant seatedAt) {
		this.tableId = tableId;
		this.userId = userId;
		this.seatedAt = seatedAt;
	}

	public static TableSeat seat(String tableId, String userId, Instant now) {
		return new TableSeat(tableId, userId, now);
	}

	public static TableSeat restore(String tableId, String userId, Instant seatedAt) {
		return new TableSeat(tableId, userId, seatedAt);
	}

	public String tableId() {
		return tableId;
	}

	public String userId() {
		return userId;
	}

	public Instant seatedAt() {
		return seatedAt;
	}
}
