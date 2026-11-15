package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

/**
 * Uma mesa: o grupo que se reúne em torno de uma campanha.
 *
 * <p>A mesa é onde as pessoas se sentam. Ela tem um dono (o mestre que a criou), um nome, uma
 * campanha e — quando existe — uma sala de voz do Épapo vinculada. A sala de voz é REFERÊNCIA,
 * não infraestrutura: a mesa não sabe se o Épapo está no ar, e continua funcionando sem voz.</p>
 *
 * <p>Uma mesa pode ser privada (o mestre convida) ou aberta a quem participa da campanha. O dono
 * da mesa é o mestre; os demais sentados são jogadores.</p>
 */
public class GameTable {

	private final String id;
	private final String campaignId;
	private final String name;
	private final String ownerId;
	/** A sala de voz do Épapo vinculada a esta mesa; nulo quando a mesa ainda não tem voz. */
	private final String voiceRoomId;
	private final boolean open;
	private final Instant createdAt;
	private final Instant updatedAt;

	private GameTable(String id, String campaignId, String name, String ownerId, String voiceRoomId, boolean open,
			Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.name = name;
		this.ownerId = ownerId;
		this.voiceRoomId = voiceRoomId;
		this.open = open;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/** Mesa nova: nasce sem sala de voz — a voz é vinculada depois, quando a mesa existe. */
	public static GameTable create(String id, String campaignId, String name, String ownerId, boolean open,
			Instant now) {
		return new GameTable(id, campaignId, name, ownerId, null, open, now, now);
	}

	public static GameTable restore(String id, String campaignId, String name, String ownerId, String voiceRoomId,
			boolean open, Instant createdAt, Instant updatedAt) {
		return new GameTable(id, campaignId, name, ownerId, voiceRoomId, open, createdAt, updatedAt);
	}

	/** Campo nulo mantém o valor atual. */
	public GameTable update(String name, Boolean open, Instant now) {
		return new GameTable(id, campaignId, name != null ? name : this.name, ownerId, voiceRoomId,
			open != null ? open : this.open, createdAt, now);
	}

	/** Vincula (ou troca) a sala de voz do Épapo. */
	public GameTable withVoiceRoom(String roomId, Instant now) {
		return new GameTable(id, campaignId, name, ownerId, roomId, open, createdAt, now);
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public String name() {
		return name;
	}

	public String ownerId() {
		return ownerId;
	}

	public String voiceRoomId() {
		return voiceRoomId;
	}

	/** Mesa aberta: quem participa da campanha pode sentar sem convite do dono. */
	public boolean isOpen() {
		return open;
	}

	public boolean hasVoiceRoom() {
		return voiceRoomId != null && !voiceRoomId.isBlank();
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
