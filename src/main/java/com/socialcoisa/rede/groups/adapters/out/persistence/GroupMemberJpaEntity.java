package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;

import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "group_members")
class GroupMemberJpaEntity {

	@Id
	private String id;

	@Column(name = "group_id", nullable = false)
	private String groupId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private GroupRole role;

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	protected GroupMemberJpaEntity() {
	}

	static GroupMemberJpaEntity fromDomain(String id, GroupMember member) {
		GroupMemberJpaEntity entity = new GroupMemberJpaEntity();
		entity.id = id;
		entity.groupId = member.groupId();
		entity.userId = member.userId();
		entity.role = member.role();
		entity.joinedAt = member.joinedAt();
		return entity;
	}

	GroupMember toDomain() {
		return GroupMember.restore(groupId, userId, role, joinedAt);
	}

	String groupId() {
		return groupId;
	}

	String userId() {
		return userId;
	}

	void changeRole(GroupRole newRole) {
		this.role = newRole;
	}
}
