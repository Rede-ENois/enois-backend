package com.socialcoisa.rede.network.application;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StandbySnapshotRepository;

/**
 * Registra que uma cópia do banco falhou, <b>fora</b> da transação da cópia.
 *
 * <p>Existe porque o caminho de falha é o que mais precisa sobreviver: quando o servidor não
 * consegue ler o banco, a exceção sobe para o nó (503) e a transação da cópia sofre rollback. Se a
 * marcação de falha participasse do rollback, a linha voltaria a {@code SYNCING} e o painel diria
 * "copiando" para sempre — o operador nunca saberia por que não há cópia. {@code REQUIRES_NEW}
 * grava a falha em transação própria, e ela fica.</p>
 */
@Component
public class StandbyFailureRecorder {

	private final StandbySnapshotRepository snapshots;

	StandbyFailureRecorder(StandbySnapshotRepository snapshots) {
		this.snapshots = snapshots;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(String nodeId, String authorityId, String reason, Instant now) {
		snapshots.markFailed(nodeId, authorityId, reason, now);
	}
}
