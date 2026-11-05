package com.socialcoisa.rede.communities.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.communities.domain.MemberRole;

@Repository
class CommunityPersistenceAdapter implements CommunityRepository {

	private final SpringDataCommunityJpaRepository springData;
	private final SpringDataCommunityMemberJpaRepository memberRepo;
	private final SpringDataFavoriteCommunityJpaRepository favoriteRepo;
	private final SpringDataCommunityPinnedPostJpaRepository pinnedRepo;

	CommunityPersistenceAdapter(SpringDataCommunityJpaRepository springData,
			SpringDataCommunityMemberJpaRepository memberRepo,
			SpringDataFavoriteCommunityJpaRepository favoriteRepo,
			SpringDataCommunityPinnedPostJpaRepository pinnedRepo) {
		this.springData = springData;
		this.memberRepo = memberRepo;
		this.favoriteRepo = favoriteRepo;
		this.pinnedRepo = pinnedRepo;
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
	public List<Community> findAll(String topic, String query, String state, String cityKey, Instant before,
			int limit) {
		PageRequest page = PageRequest.of(0, limit);
		String q = com.socialcoisa.rede.shared.web.LikePattern.contains(query);
		List<CommunityJpaEntity> entities;
		if (StringUtils.hasText(topic)) {
			entities = springData.findByTopic(topic, q, state, cityKey, before, page);
		} else {
			entities = springData.findAllBefore(q, state, cityKey, before, page);
		}
		return entities.stream().map(CommunityJpaEntity::toDomain).toList();
	}

	@Override
	public List<Community> findLocal(String state, String cityKey, int limit) {
		return springData.findLocal(state, cityKey, PageRequest.of(0, limit)).stream()
			.map(CommunityJpaEntity::toDomain).toList();
	}

	@Override
	public List<Community> findByMember(String userId, int limit) {
		List<String> communityIds = memberRepo.findByUserId(userId).stream()
			.map(CommunityMemberJpaEntity::communityId)
			.limit(limit)
			.toList();
		if (communityIds.isEmpty()) {
			return List.of();
		}
		return springData.findAllById(communityIds).stream().map(CommunityJpaEntity::toDomain).toList();
	}

	@Override
	public void saveMember(CommunityMember member) {
		memberRepo.save(CommunityMemberJpaEntity.fromDomain(member));
	}

	@Override
	public void updateMemberRole(String communityId, String userId, MemberRole role) {
		memberRepo.findByCommunityIdAndUserId(communityId, userId).ifPresent(entity -> {
			entity.changeRole(role);
			memberRepo.save(entity);
		});
	}

	@Override
	public void removeAllMembers(String communityId) {
		memberRepo.deleteByCommunityId(communityId);
	}

	@Override
	public Optional<CommunityMember> findMember(String communityId, String userId) {
		return memberRepo.findByCommunityIdAndUserId(communityId, userId).map(CommunityMemberJpaEntity::toDomain);
	}

	@Override
	public void savePinnedPost(String communityId, String postId, String pinnedBy, Instant pinnedAt) {
		// O id e a comunidade: salvar substitui o fixado anterior na mesma transacao.
		pinnedRepo.save(new CommunityPinnedPostJpaEntity(communityId, postId, pinnedBy, pinnedAt));
	}

	@Override
	public Optional<String> findPinnedPostId(String communityId) {
		return pinnedRepo.findById(communityId).map(CommunityPinnedPostJpaEntity::postId);
	}

	@Override
	public void removePinnedPost(String communityId) {
		pinnedRepo.deleteById(communityId);
	}

	@Override
	public void addFavorite(String communityId, String userId, Instant createdAt) {
		if (favoriteRepo.existsByUserIdAndCommunityId(userId, communityId)) {
			return;
		}
		String id = "fav_" + java.util.UUID.randomUUID().toString().replace("-", "");
		favoriteRepo.save(new FavoriteCommunityJpaEntity(id, communityId, userId, createdAt));
	}

	@Override
	public boolean removeFavorite(String communityId, String userId) {
		if (!favoriteRepo.existsByUserIdAndCommunityId(userId, communityId)) {
			return false;
		}
		favoriteRepo.deleteByUserIdAndCommunityId(userId, communityId);
		return true;
	}

	@Override
	public List<Community> findFavorites(String userId, int limit) {
		List<String> ids = favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
			.map(FavoriteCommunityJpaEntity::getCommunityId)
			.limit(limit)
			.toList();
		if (ids.isEmpty()) {
			return List.of();
		}
		// findById em lote preservando a ordem de favoritagem.
		var byId = springData.findAllById(ids).stream()
			.collect(java.util.stream.Collectors.toMap(entity -> entity.toDomain().id(), entity -> entity));
		return ids.stream().map(byId::get).filter(java.util.Objects::nonNull)
			.map(CommunityJpaEntity::toDomain).toList();
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
	public java.util.Map<String, Integer> countMembersByCommunityIds(java.util.Collection<String> communityIds) {
		if (communityIds == null || communityIds.isEmpty()) {
			return java.util.Map.of();
		}
		java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		for (Object[] row : memberRepo.countByCommunityIds(communityIds)) {
			counts.put((String) row[0], ((Number) row[1]).intValue());
		}
		return counts;
	}

	@Override
	public java.util.Set<String> memberCommunityIds(String userId, java.util.Collection<String> communityIds) {
		if (userId == null || communityIds == null || communityIds.isEmpty()) {
			return java.util.Set.of();
		}
		return new java.util.HashSet<>(memberRepo.findCommunityIdsByUserIdAndCommunityIdsIn(userId, communityIds));
	}

	@Override
	public List<CommunityMember> findMembersByCommunity(String communityId) {
		return memberRepo.findByCommunityId(communityId).stream()
			.map(CommunityMemberJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void delete(String communityId) {
		springData.deleteById(communityId);
	}
}
