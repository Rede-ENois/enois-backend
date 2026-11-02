package com.socialcoisa.rede.communities.domain;

import java.time.Instant;

import com.socialcoisa.rede.shared.geo.CityState;

public class Community {

	private final String id;
	private final String name;
	private final String description;
	private final String topic;
	private final String cover;
	private final String avatar;
	/** Capa em HTML/CSS sanitizado (mesma tubulação da capa do perfil); nulo = usa a foto da capa. */
	private final String bannerHtml;
	/** Cidade + UF da comunidade local ("Devs de BH"); nulo = comunidade sem lugar. */
	private final CityState place;
	private final String createdBy;
	private final Instant createdAt;
	private final Instant updatedAt;

	private Community(String id, String name, String description, String topic, String cover, String avatar,
			String bannerHtml, CityState place, String createdBy, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.name = name;
		this.description = description;
		this.topic = topic;
		this.cover = cover;
		this.avatar = avatar;
		this.bannerHtml = bannerHtml;
		this.place = place;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static Community create(String id, String name, String description, String topic, String cover, String avatar,
			CityState place, String createdBy, Instant now) {
		return new Community(id, name, description, topic, cover, avatar, null, place, createdBy, now, now);
	}

	public static Community restore(String id, String name, String description, String topic, String cover, String avatar,
			String bannerHtml, CityState place, String createdBy, Instant createdAt, Instant updatedAt) {
		return new Community(id, name, description, topic, cover, avatar, bannerHtml, place, createdBy, createdAt,
			updatedAt);
	}

	/**
	 * `bannerHtml` nulo mantem o valor atual (campo ausente no PATCH); string vazia limpa. O `place`
	 * ja vem resolvido pelo servico (manter, trocar ou limpar).
	 */
	public Community update(String name, String description, String topic, String cover, String avatar,
			String bannerHtml, CityState place, Instant now) {
		String nextBannerHtml = bannerHtml == null ? this.bannerHtml
			: (bannerHtml.isEmpty() ? null : bannerHtml);
		return new Community(id, name, description, topic, cover, avatar, nextBannerHtml, place, createdBy, createdAt,
			now);
	}

	/**
	 * Passa a propriedade para outro membro. A propriedade e o que autoriza editar e excluir, entao
	 * a transferencia precisa ser explicita — sair da comunidade nao pode deixar o dono sem controle
	 * nem largar a comunidade sem administracao.
	 */
	public Community transferOwnership(String newOwnerId, Instant now) {
		return new Community(id, name, description, topic, cover, avatar, bannerHtml, place, newOwnerId, createdAt,
			now);
	}

	public String id() { return id; }
	public String name() { return name; }
	public String description() { return description; }
	public String topic() { return topic; }
	public String cover() { return cover; }
	public String avatar() { return avatar; }
	public String bannerHtml() { return bannerHtml; }
	public CityState place() { return place; }
	public String createdBy() { return createdBy; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }

}
