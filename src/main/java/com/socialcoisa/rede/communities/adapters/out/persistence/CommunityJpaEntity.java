package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.shared.geo.CityState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "communities", indexes = {@Index(name = "idx_communities_topic", columnList = "topic"),
	@Index(name = "idx_communities_state_city", columnList = "state, city_key")})
class CommunityJpaEntity {

	@Id
	private String id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, length = 2000)
	private String description;

	private String topic;

	@Column(length = 500)
	private String cover;

	@Column(length = 500)
	private String avatar;

	/** Capa em HTML/CSS sanitizado; nulo = usa a foto da `cover`. */
	@Column(name = "banner_html", columnDefinition = "longtext")
	private String bannerHtml;

	@Column(length = 80)
	private String city;

	@Column(length = 2)
	private String state;

	/** Cidade sem acento e minuscula (ver CityState.key): e por ela que o filtro compara. */
	@Column(name = "city_key", length = 80)
	private String cityKey;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CommunityJpaEntity() {
	}

	static CommunityJpaEntity fromDomain(Community community) {
		CommunityJpaEntity e = new CommunityJpaEntity();
		e.id = community.id();
		e.name = community.name();
		e.description = community.description();
		e.topic = community.topic();
		e.cover = community.cover();
		e.avatar = community.avatar();
		e.bannerHtml = community.bannerHtml();
		if (community.place() != null) {
			e.city = community.place().city();
			e.state = community.place().state();
			e.cityKey = community.place().cityKey();
		}
		e.createdBy = community.createdBy();
		e.createdAt = community.createdAt();
		e.updatedAt = community.updatedAt();
		return e;
	}

	Community toDomain() {
		CityState place = city == null || state == null ? null : new CityState(city, state, cityKey);
		return Community.restore(id, name, description, topic, cover, avatar, bannerHtml, place, createdBy,
			createdAt, updatedAt);
	}

}
