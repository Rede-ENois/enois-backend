package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupJoinMode;
import com.socialcoisa.rede.groups.domain.GroupStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "groups")
class GroupJpaEntity {

	@Id
	private String id;

	@Column(nullable = false, length = 80)
	private String slug;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(length = 600)
	private String description;

	@Column(length = 40)
	private String topic;

	@Column(length = 500)
	private String avatar;

	@Column(length = 500)
	private String banner;

	@Column(name = "banner_html", columnDefinition = "longtext")
	private String bannerHtml;

	@Enumerated(EnumType.STRING)
	@Column(name = "join_mode", nullable = false, length = 16)
	private GroupJoinMode joinMode;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private GroupStatus status;

	@Column(name = "owner_id", nullable = false)
	private String ownerId;

	@Column(name = "member_count", nullable = false)
	private int memberCount;

	@Column(name = "post_count", nullable = false)
	private int postCount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	protected GroupJpaEntity() {
	}

	static GroupJpaEntity fromDomain(Group group) {
		GroupJpaEntity entity = new GroupJpaEntity();
		entity.id = group.id();
		entity.slug = group.slug();
		entity.name = group.name();
		entity.description = group.description();
		entity.topic = group.topic();
		entity.avatar = group.avatar();
		entity.banner = group.banner();
		entity.bannerHtml = group.bannerHtml();
		entity.joinMode = group.joinMode();
		entity.status = group.status();
		entity.ownerId = group.ownerId();
		entity.createdAt = group.createdAt();
		entity.updatedAt = group.updatedAt();
		entity.suspendedAt = group.suspendedAt();
		return entity;
	}

	Group toDomain() {
		return Group.restore(id, slug, name, description, topic, avatar, banner, bannerHtml, joinMode, status, ownerId,
			createdAt, updatedAt, suspendedAt);
	}

	String id() {
		return id;
	}

	/** O contador desnormalizado nunca desce de zero, mesmo com remoção repetida. */
	void adjustMemberCount(int delta) {
		memberCount = Math.max(0, memberCount + delta);
	}
}
