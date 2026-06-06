package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.communities.domain.MemberRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "community_members",
	uniqueConstraints = @UniqueConstraint(name = "uk_community_member", columnNames = { "community_id", "user_id" }),
	indexes = {
		@Index(name = "idx_community_members_community", columnList = "community_id"),
		@Index(name = "idx_community_members_user", columnList = "user_id") })
class CommunityMemberJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "community_id", nullable = false)
	private String communityId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberRole role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	protected CommunityMemberJpaEntity() {
	}

	static CommunityMemberJpaEntity fromDomain(CommunityMember member) {
		CommunityMemberJpaEntity e = new CommunityMemberJpaEntity();
		e.id = member.id();
		e.communityId = member.communityId();
		e.userId = member.userId();
		e.role = member.role();
		e.joinedAt = member.joinedAt();
		return e;
	}

	CommunityMember toDomain() {
		return CommunityMember.restore(id, communityId, userId, role, joinedAt);
	}

}
