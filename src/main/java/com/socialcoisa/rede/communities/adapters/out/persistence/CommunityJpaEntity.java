package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.communities.domain.Community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "communities", indexes = @Index(name = "idx_communities_topic", columnList = "topic"))
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
		e.createdBy = community.createdBy();
		e.createdAt = community.createdAt();
		e.updatedAt = community.updatedAt();
		return e;
	}

	Community toDomain() {
		return Community.restore(id, name, description, topic, cover, avatar, createdBy, createdAt, updatedAt);
	}

}
