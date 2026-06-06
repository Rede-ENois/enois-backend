package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;

@Repository
class CommunityPersistenceAdapter implements CommunityRepository {

	private final SpringDataCommunityJpaRepository springData;
	private final SpringDataCommunityMemberJpaRepository memberRepo;

	CommunityPersistenceAdapter(SpringDataCommunityJpaRepository springData,
			SpringDataCommunityMemberJpaRepository memberRepo) {
		this.springData = springData;
		this.memberRepo = memberRepo;
	}

	@Override
	public void save(Community community) {
		springData.save(CommunityJpaEntity.fromDomain(community));
	}

	@Override
	public Optional<Community> findById(String id) {
		return springData.findById(id).map(CommunityJpaEntity::toDomain);
	}

	@Override
	public List<Community> findAll(String topic, int limit) {
		List<CommunityJpaEntity> entities;
		if (StringUtils.hasText(topic)) {
			entities = springData.findByTopicOrderByCreatedAtDesc(topic);
		} else {
			entities = springData.findAllByOrderByCreatedAtDesc();
		}
		return entities.stream().map(CommunityJpaEntity::toDomain).limit(limit).toList();
	}

	@Override
	public void saveMember(CommunityMember member) {
		memberRepo.save(CommunityMemberJpaEntity.fromDomain(member));
	}

	@Override
	public void removeMember(String communityId, String userId) {
		memberRepo.deleteByCommunityIdAndUserId(communityId, userId);
	}

	@Override
	public boolean isMember(String communityId, String userId) {
		return memberRepo.existsByCommunityIdAndUserId(communityId, userId);
	}

	@Override
	public int countMembers(String communityId) {
		return memberRepo.countByCommunityId(communityId);
	}

	@Override
	public List<CommunityMember> findMembersByCommunity(String communityId) {
		return memberRepo.findByCommunityId(communityId).stream()
			.map(CommunityMemberJpaEntity::toDomain)
			.toList();
	}

}
