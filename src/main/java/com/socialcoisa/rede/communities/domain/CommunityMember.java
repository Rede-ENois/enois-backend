package com.socialcoisa.rede.communities.domain;

import java.time.Instant;

public class CommunityMember {

	private final Long id;
	private final String communityId;
	private final String userId;
	private final MemberRole role;
	private final Instant joinedAt;

	private CommunityMember(Long id, String communityId, String userId, MemberRole role, Instant joinedAt) {
		this.id = id;
		this.communityId = communityId;
		this.userId = userId;
		this.role = role;
		this.joinedAt = joinedAt;
	}

	public static CommunityMember join(String communityId, String userId, MemberRole role, Instant now) {
		return new CommunityMember(null, communityId, userId, role, now);
	}

	public static CommunityMember restore(Long id, String communityId, String userId, MemberRole role, Instant joinedAt) {
		return new CommunityMember(id, communityId, userId, role, joinedAt);
	}

	public Long id() { return id; }

	public String communityId() { return communityId; }

	public String userId() { return userId; }

	public MemberRole role() { return role; }

	public CommunityMember promoteToAdmin() {
		return new CommunityMember(id, communityId, userId, MemberRole.ADMIN, joinedAt);
	}

	public Instant joinedAt() { return joinedAt; }

}
