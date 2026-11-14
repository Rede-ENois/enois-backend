package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;

/**
 * Uma campanha: o mundo que o mestre construiu e onde o grupo joga.
 *
 * <p>A campanha é a fronteira de isolamento do Éroleplay. Tudo que é secreto — ficha de jogador,
 * segredo do mestre, anotação privada — pertence a uma campanha e não existe fora dela. Por isso
 * ela tem tabela própria, e não uma coluna {@code campaign_id} em tabela alheia: o isolamento é
 * estrutural, como nos Grupos.</p>
 *
 * <p>A campanha guarda o SISTEMA DE REGRAS que a mesa usa. Guardar o identificador do sistema — e
 * não as regras em si — é o que permite trocar de adapter sem reescrever o mundo: o Registry
 * (Fase 4) resolve o identificador para um adapter, e o adapter carrega a semântica.</p>
 *
 * <p>Segue a régua do PATCH do resto da plataforma: campo nulo mantém o valor atual; string vazia
 * limpa o que dá para limpar.</p>
 */
public class Campaign {

	private final String id;
	private final String slug;
	private final String name;
	private final String description;
	/** A ambientação: o mundo onde a campanha acontece (o "setting" que o mestre descreve). */
	private final String setting;
	/** O identificador do sistema de regras (resolvido pelo Registry para um adapter). */
	private final String gameSystem;
	/** A família do motor que o sistema usa (D20, D100, autoral) — decide como o motor interpreta. */
	private final GameSystemFamily systemFamily;
	private final CampaignVisibility visibility;
	private final CampaignStatus status;
	private final String ownerId;
	private final int memberCount;
	private final Instant createdAt;
	private final Instant updatedAt;

	private Campaign(String id, String slug, String name, String description, String setting, String gameSystem,
			GameSystemFamily systemFamily, CampaignVisibility visibility, CampaignStatus status, String ownerId,
			int memberCount, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.slug = slug;
		this.name = name;
		this.description = description;
		this.setting = setting;
		this.gameSystem = gameSystem;
		this.systemFamily = systemFamily;
		this.visibility = visibility;
		this.status = status;
		this.ownerId = ownerId;
		this.memberCount = memberCount;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/** Campanha nova: nasce ATIVA, com o sistema escolhido e o mestre como dono. */
	public static Campaign create(String id, String slug, String name, String description, String setting,
			String gameSystem, GameSystemFamily systemFamily, CampaignVisibility visibility, String ownerId,
			Instant now) {
		return new Campaign(id, slug, name, description, setting, gameSystem, systemFamily, visibility,
			CampaignStatus.ACTIVE, ownerId, 0, now, now);
	}

	public static Campaign restore(String id, String slug, String name, String description, String setting,
			String gameSystem, GameSystemFamily systemFamily, CampaignVisibility visibility, CampaignStatus status,
			String ownerId, int memberCount, Instant createdAt, Instant updatedAt) {
		return new Campaign(id, slug, name, description, setting, gameSystem, systemFamily, visibility, status,
			ownerId, memberCount, createdAt, updatedAt);
	}

	/**
	 * Campo nulo mantém o valor atual; string vazia limpa.
	 *
	 * <p>Trocar o sistema de regras é decisão do mestre e vale na campanha inteira — mas não
	 * acontece por engano: o serviço exige que a campanha não tenha ficha preenchida antes de
	 * aceitar a troca, porque ficha feita em D20 não vira ficha de D100 sozinha.</p>
	 */
	public Campaign update(String name, String description, String setting, String gameSystem,
			GameSystemFamily systemFamily, CampaignVisibility visibility, Instant now) {
		return new Campaign(id, slug, name != null ? name : this.name,
			description != null ? description : this.description,
			setting != null ? setting : this.setting,
			gameSystem != null ? gameSystem : this.gameSystem,
			systemFamily != null ? systemFamily : this.systemFamily,
			visibility != null ? visibility : this.visibility,
			status, ownerId, memberCount, createdAt, now);
	}

	/** Troca o dono (o mestre que passa a comandar a campanha). */
	public Campaign withOwner(String newOwnerId, Instant now) {
		return new Campaign(id, slug, name, description, setting, gameSystem, systemFamily, visibility, status,
			newOwnerId, memberCount, createdAt, now);
	}

	/** Muda o estado (arquivar ou encerrar). */
	public Campaign withStatus(CampaignStatus newStatus, Instant now) {
		return new Campaign(id, slug, name, description, setting, gameSystem, systemFamily, visibility, newStatus,
			ownerId, memberCount, createdAt, now);
	}

	/** Contador de participantes desnormalizado, ajustado no banco. */
	public Campaign withMemberCount(int newCount) {
		return new Campaign(id, slug, name, description, setting, gameSystem, systemFamily, visibility, status,
			ownerId, Math.max(0, newCount), createdAt, updatedAt);
	}

	public String id() {
		return id;
	}

	public String slug() {
		return slug;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public String setting() {
		return setting;
	}

	public String gameSystem() {
		return gameSystem;
	}

	public GameSystemFamily systemFamily() {
		return systemFamily;
	}

	public CampaignVisibility visibility() {
		return visibility;
	}

	public CampaignStatus status() {
		return status;
	}

	public String ownerId() {
		return ownerId;
	}

	public int memberCount() {
		return memberCount;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}
}
