package com.socialcoisa.rede.moderation.adapters.out.persistence;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.UserSanction;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;

/** Implementa a porta compartilhada de enforcement com o estado real das sancos. */
@Component
class SanctionGateAdapter implements SanctionGateReader {

	private final SanctionRepository sanctions;

	SanctionGateAdapter(SanctionRepository sanctions) {
		this.sanctions = sanctions;
	}

	@Override
	public boolean restricted(String userId) {
		return sanctions.findActiveByUser(userId).isPresent();
	}

	@Override
	public boolean banned(String userId) {
		return sanctions.findActiveByUser(userId).map(UserSanction::isBlocked).orElse(false);
	}

	@Override
	public Set<String> bannedAmong(Collection<String> userIds) {
		// At most one active sanction per account, so "any active one blocks" is the same answer as
		// banned(userId) for each id.
		return sanctions.findActiveByUsers(userIds).stream()
			.filter(UserSanction::isBlocked)
			.map(UserSanction::userId)
			.collect(Collectors.toSet());
	}

	@Override
	public Optional<String> activeReason(String userId) {
		return sanctions.findActiveByUser(userId).map(UserSanction::reason);
	}
}
