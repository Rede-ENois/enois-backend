package com.socialcoisa.rede.identity.adapters.out.persistence;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.identity.application.port.out.DiditVerificationGrantRepository;

@Component
class DiditVerificationGrantPersistenceAdapter implements DiditVerificationGrantRepository {

	private final SpringDataDiditVerificationGrantJpaRepository grants;

	DiditVerificationGrantPersistenceAdapter(SpringDataDiditVerificationGrantJpaRepository grants) {
		this.grants = grants;
	}

	@Override
	public boolean hasGrant(String userId) {
		return grants.findByUserIdAndRevokedAtIsNull(userId).isPresent();
	}

	@Override
	@Transactional
	public void grant(String userId, String roundId, int reputation, Instant now) {
		// Uma rodada reexecutada nao pode resetar a data de liberacao original: o convite e a
		// prova de quando a vez chegou.
		if (grants.existsById(userId)) {
			return;
		}
		grants.save(DiditVerificationGrantJpaEntity.fresh(userId, roundId, reputation, now));
	}

	@Override
	@Transactional
	public void markConsumed(String userId, Instant now) {
		grants.findByUserIdAndRevokedAtIsNull(userId).ifPresent(grant -> {
			grant.consume(now);
			grants.save(grant);
		});
	}

	@Override
	@Transactional
	public void revoke(String userId, Instant now) {
		grants.findByUserIdAndRevokedAtIsNull(userId).ifPresent(grant -> {
			grant.revoke(now);
			grants.save(grant);
		});
	}
}
