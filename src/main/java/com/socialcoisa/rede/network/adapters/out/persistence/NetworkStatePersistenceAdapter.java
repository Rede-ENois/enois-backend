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

	/**
	 * Grava o modo vigente.
	 *
	 * <p>Antes: sem linha em {@code network_state}, a entidade era criada já com o modo novo e a
	 * comparação "o modo mudou?" saía antes do {@code save}. A primeira transição nunca era gravada e
	 * {@code current()} respondia {@code NORMAL} para sempre num banco recém-migrado — a V277 cria a
	 * tabela e não insere a linha. Agora: sem linha, insere direto; com linha, só grava se o modo
	 * mudou, como antes.</p>
	 */
	@Override
	public void setMode(NetworkMode mode, String reason, Instant now) {
		Optional<NetworkStateJpaEntity> found = repository.findById(GLOBAL);
		if (found.isEmpty()) {
			repository.save(new NetworkStateJpaEntity(GLOBAL, mode.name(), reason, now));
			return;
		}
		NetworkStateJpaEntity entity = found.get();
		if (entity.toMode() == mode) {
			return;
		}
		entity.change(mode.name(), reason, now);
		repository.save(entity);
	}
}
