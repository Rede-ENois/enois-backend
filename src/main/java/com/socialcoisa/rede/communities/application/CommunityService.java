package com.socialcoisa.rede.communities.application;

import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.communities.domain.MemberRole;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class CommunityService {

	private final CommunityRepository repository;
	private final Clock clock;

	public CommunityService(CommunityRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional
	public Community create(String name, String description, String topic, String cover, String avatar, String userId) {
		String id = "community_" + UUID.randomUUID().toString().replace("-", "");
		Community community = Community.create(id, name, description, topic, cover, avatar, userId, clock.instant());
		repository.save(community);
		repository.saveMember(CommunityMember.join(id, userId, MemberRole.ADMIN, clock.instant()));
		return community;
	}

	@Transactional
	public Community update(String communityId, String name, String description, String topic, String cover,
			String avatar, String userId) {
		Community community = repository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
		if (!community.createdBy().equals(userId)) {
			throw new ApiException(403, "FORBIDDEN", "So o criador pode atualizar a comunidade.");
		}
		String finalName = name != null ? name : community.name();
		String finalDesc = description != null ? description : community.description();
		String finalTopic = topic != null ? topic : community.topic();
		String finalCover = cover != null ? cover : community.cover();
		String finalAvatar = avatar != null ? avatar : community.avatar();
		Community updated = community.update(finalName, finalDesc, finalTopic, finalCover, finalAvatar, clock.instant());
		repository.save(updated);
		return updated;
	}

	@Transactional
	public CommunityMember join(String communityId, String userId) {
		if (repository.isMember(communityId, userId)) {
			throw new ApiException(409, "ALREADY_MEMBER", "Voce ja e membro desta comunidade.");
		}
		CommunityMember member = CommunityMember.join(communityId, userId, MemberRole.MEMBER, clock.instant());
		repository.saveMember(member);
		return member;
	}

	@Transactional
	public void leave(String communityId, String userId) {
		if (!repository.isMember(communityId, userId)) {
			throw new ApiException(404, "NOT_MEMBER", "Voce nao e membro desta comunidade.");
		}
		boolean wasAdmin = repository.findMembersByCommunity(communityId).stream()
			.anyMatch(m -> m.userId().equals(userId) && m.role() == MemberRole.ADMIN);
		repository.removeMember(communityId, userId);
		int remaining = repository.countMembers(communityId);
		if (remaining == 0) {
			repository.delete(communityId);
			return;
		}
		if (wasAdmin) {
			List<CommunityMember> members = repository.findMembersByCommunity(communityId);
			CommunityMember newAdmin = members.get(new Random().nextInt(members.size()));
			repository.saveMember(newAdmin.promoteToAdmin());
		}
	}

	public List<Community> list(String topic, java.time.Instant before, int limit) {
		return repository.findAll(topic, before, limit);
	}

	public Community getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade nao encontrada."));
	}

	public boolean isMember(String communityId, String userId) {
		return repository.isMember(communityId, userId);
	}

	public int countMembers(String communityId) {
		return repository.countMembers(communityId);
	}

	public List<CommunityMember> findMembers(String communityId) {
		return repository.findMembersByCommunity(communityId);
	}

}
