package com.socialcoisa.rede.wrapped.application.port.out;

import java.time.Instant;
import java.util.List;

/** As comunidades da conta no ano: as que ela entrou (no ano) ou onde ela publicou (no ano). */
public interface WrappedComunidadesRepository {

	List<Participacao> daConta(String userId, Instant de, Instant ate);

	record Participacao(String communityId, String nome, boolean membro, int publicacoes) {
	}
}
