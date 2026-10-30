package com.socialcoisa.rede.network.adapters.out.persistence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.UserBlockRepository;
import com.socialcoisa.rede.network.domain.UserBlock;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;

@Repository
class UserBlockPersistenceAdapter implements UserBlockRepository, BlockedUsersReader {

	private final SpringDataUserBlockJpaRepository repository;
	private final SpringDataUserMuteJpaRepository muteRepository;

	UserBlockPersistenceAdapter(SpringDataUserBlockJpaRepository repository,
			SpringDataUserMuteJpaRepository muteRepository) {
		this.repository = repository;
		this.muteRepository = muteRepository;
	}

	@Override
	public void save(UserBlock block) {
		repository.findByBlockerIdAndBlockedId(block.blockerId(), block.blockedId())
			.ifPresentOrElse(existing -> { }, () -> repository.save(UserBlockJpaEntity.fromDomain(block)));
	}

	@Override
	public void delete(String blockerId, String blockedId) {
		repository.findByBlockerIdAndBlockedId(blockerId, blockedId)
			.ifPresent(repository::delete);
	}

	@Override
	public boolean exists(String blockerId, String blockedId) {
		return repository.findByBlockerIdAndBlockedId(blockerId, blockedId).isPresent();
	}

	@Override
	public boolean hiddenBetween(String userA, String userB) {
		return exists(userA, userB) || exists(userB, userA);
	}

	@Override
	public List<UserBlock> findByBlockerId(String blockerId) {
		return repository.findByBlockerIdOrderByCreatedAtDesc(blockerId).stream()
			.map(UserBlockJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Set<String> findBlockedIds(String blockerId) {
		return repository.findByBlockerIdOrderByCreatedAtDesc(blockerId).stream()
			.map(UserBlockJpaEntity::toDomain)
			.map(UserBlock::blockedId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	@Override
	public Set<String> hiddenIdsFor(String userId) {
		if (userId == null || userId.isBlank()) {
			return Set.of();
		}
		return findHiddenIds(userId);
	}

	@Override
	public Set<String> findHiddenIds(String userId) {
		Set<String> hidden = findBlockedIds(userId);
		repository.findByBlockedId(userId).stream()
			.map(UserBlockJpaEntity::toDomain)
			.map(UserBlock::blockerId)
			.forEach(hidden::add);
		muteRepository.findByMuterIdOrderByCreatedAtDesc(userId).stream()
			.map(UserMuteJpaEntity::toDomain)
			.map(com.socialcoisa.rede.network.domain.UserMute::mutedId)
			.forEach(hidden::add);
		return hidden;
	}
}