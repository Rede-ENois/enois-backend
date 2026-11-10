package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.groups.application.port.out.GroupRepository;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupJoinRequest;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupMembership;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.groups.domain.GroupStatus;
import com.socialcoisa.rede.groups.domain.JoinRequestStatus;

@Repository
class GroupPersistenceAdapter implements GroupRepository {

	/** O que a descoberta mostra: grupo ativo ou restrito. Suspenso e encerrado não aparecem. */
	private static final List<GroupStatus> DISCOVERABLE = List.of(GroupStatus.ACTIVE, GroupStatus.RESTRICTED);

	/** Complemento de {@link GroupStatus#readableByMembers()}: o que some da lista de quem participa. */
	private static final List<GroupStatus> NOT_READABLE_BY_MEMBERS = java.util.Arrays.stream(GroupStatus.values())
		.filter(status -> !status.readableByMembers())
		.toList();

	private final SpringDataGroupJpaRepository groups;
	private final SpringDataGroupMemberJpaRepository members;
	private final SpringDataGroupJoinRequestJpaRepository requests;

	GroupPersistenceAdapter(SpringDataGroupJpaRepository groups, SpringDataGroupMemberJpaRepository members,
			SpringDataGroupJoinRequestJpaRepository requests) {
		this.groups = groups;
		this.members = members;
		this.requests = requests;
	}

	@Override
	public void save(Group group) {
		groups.save(GroupJpaEntity.fromDomain(group));
	}

	@Override
	public Optional<Group> findById(String id) {
		return groups.findById(id).map(GroupJpaEntity::toDomain);
	}

	@Override
	public Optional<Group> findBySlug(String slug) {
		return groups.findBySlug(slug).map(GroupJpaEntity::toDomain);
	}

	@Override
	public boolean existsBySlug(String slug) {
		return groups.existsBySlug(slug);
	}

	@Override
	public List<Group> listVisible(String query, Instant before, int limit) {
		String like = com.socialcoisa.rede.shared.web.LikePattern.contains(query);
		return groups.listVisible(DISCOVERABLE, like, before, PageRequest.of(0, limit))
			.stream()
			.map(GroupJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<GroupMembership> findMemberships(String userId, int limit) {
		return members.findMemberships(userId, NOT_READABLE_BY_MEMBERS, PageRequest.of(0, limit)).stream()
			.map(row -> new GroupMembership(((GroupJpaEntity) row[0]).toDomain(), (GroupRole) row[1]))
			.toList();
	}

	@Override
	public Map<String, Long> countMembers(Collection<String> groupIds) {
		if (groupIds.isEmpty()) {
			return Map.of();
		}
		return members.countByGroupIds(groupIds).stream()
			.collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).longValue()));
	}

	@Override
	public void saveMember(GroupMember member) {
		members.findByGroupIdAndUserId(member.groupId(), member.userId()).ifPresentOrElse(existing -> {
			existing.changeRole(member.role());
			members.save(existing);
		}, () -> members.save(GroupMemberJpaEntity.fromDomain("group_member_" + newId(), member)));
	}

	@Override
	public Optional<GroupMember> findMember(String groupId, String userId) {
		return members.findByGroupIdAndUserId(groupId, userId).map(GroupMemberJpaEntity::toDomain);
	}

	@Override
	public List<GroupMember> listMembers(String groupId) {
		return members.findByGroupIdOrderByJoinedAtAsc(groupId).stream().map(GroupMemberJpaEntity::toDomain).toList();
	}

	@Override
	public long countMembers(String groupId) {
		return members.countByGroupId(groupId);
	}

	@Override
	public void removeMember(String groupId, String userId) {
		members.deleteByGroupIdAndUserId(groupId, userId);
	}

	@Override
	@Transactional
	public void adjustMemberCount(String groupId, int delta) {
		// UPDATE atomico no banco: ler-mutar-gravar perdia incremento quando duas entradas corriam
		// juntas (24 simultaneas deixavam o contador em 1). O piso zero vive no proprio UPDATE.
		groups.adjustMemberCount(groupId, delta);
	}

	@Override
	public void saveJoinRequest(GroupJoinRequest request) {
		requests.save(GroupJoinRequestJpaEntity.fromDomain(request));
	}

	@Override
	public Optional<GroupJoinRequest> findPendingRequest(String groupId, String userId) {
		return requests.findByGroupIdAndUserIdAndStatus(groupId, userId, JoinRequestStatus.PENDING)
			.map(GroupJoinRequestJpaEntity::toDomain);
	}

	@Override
	public Optional<GroupJoinRequest> findRequestById(String id) {
		return requests.findById(id).map(GroupJoinRequestJpaEntity::toDomain);
	}

	@Override
	public List<GroupJoinRequest> listPendingRequests(String groupId) {
		return requests.findByGroupIdAndStatusOrderByCreatedAtAsc(groupId, JoinRequestStatus.PENDING)
			.stream()
			.map(GroupJoinRequestJpaEntity::toDomain)
			.toList();
	}

	@Override
	public int markDecided(String requestId, JoinRequestStatus status, String actorId, Instant now) {
		return requests.markDecided(requestId, status, JoinRequestStatus.PENDING, actorId, now);
	}

	private String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}
}
