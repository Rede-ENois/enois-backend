package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.NetworkMode;

/**
 * Modo operacional vigente da rede. Uma linha só ({@code global}): o modo é do Control Plane, não
 * de cada nó, e dois modos ao mesmo tempo seriam dois controladores.
 */
public interface NetworkStateRepository {

	Optional<StateRecord> current();

	/** Grava o modo. Só muda quando o modo é diferente do atual. */
	void setMode(NetworkMode mode, String reason, Instant now);

	record StateRecord(NetworkMode mode, String reason, Instant since) {
	}
}
