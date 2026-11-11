package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkStateRepository;
import com.socialcoisa.rede.network.domain.NetworkMode;

@Repository
class NetworkStatePersistenceAdapter implements NetworkStateRepository {

	/** Uma linha só: o modo é global, e um segundo registro seria um segundo controlador. */
	private static final String GLOBAL = "global";

	private final SpringDataNetworkStateJpaRepository repository;

	NetworkStatePersistenceAdapter(SpringDataNetworkStateJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<StateRecord> current() {
		return repository.findById(GLOBAL)
			.map(entity -> new StateRecord(entity.toMode(), entity.reason(), entity.since()));
	}

	@Override
	public void setMode(NetworkMode mode, String reason, Instant now) {
		NetworkStateJpaEntity entity = repository.findById(GLOBAL)
			.orElseGet(() -> new NetworkStateJpaEntity(GLOBAL, mode.name(), reason, now));
		if (entity.toMode() == mode) {
			return;
		}
		entity.change(mode.name(), reason, now);
		repository.save(entity);
	}
}
