package com.socialcoisa.rede.identity.adapters.out.persistence;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.identity.application.DiditCapacityService;
import com.socialcoisa.rede.identity.application.port.out.IdentityReleaseGate;
import com.socialcoisa.rede.identity.application.port.out.DiditVerificationGrantRepository;

/**
 * Liga a liberacao progressiva ao fluxo de verificacao.
 *
 * <p>O convite e a capacidade andam juntos: a checagem de capacidade vem antes da sessao ser
 * criada no provedor, e o consumo do convite vem depois de a sessao existir — assim uma falha
 * tecnica no meio nao queima o convite da pessoa sem ela ter chance de verificar.</p>
 */
@Component
class ReleaseGateAdapter implements IdentityReleaseGate {

	private final DiditVerificationGrantRepository grants;
	private final DiditCapacityService capacity;

	ReleaseGateAdapter(DiditVerificationGrantRepository grants, DiditCapacityService capacity) {
		this.grants = grants;
		this.capacity = capacity;
	}

	@Override
	public boolean granted(String userId) {
		return grants.hasGrant(userId);
	}

	@Override
	public void consume(String userId) {
		grants.markConsumed(userId, java.time.Instant.now());
	}

	@Override
	public void requireCapacity() {
		capacity.requireCapacity();
	}
}