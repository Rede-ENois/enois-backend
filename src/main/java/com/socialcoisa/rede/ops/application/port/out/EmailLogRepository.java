package com.socialcoisa.rede.ops.application.port.out;

import java.util.List;

import com.socialcoisa.rede.ops.domain.EmailLog;

public interface EmailLogRepository {

	EmailLog save(EmailLog log);

	/** Mais recentes primeiro; o painel mostra apenas metadados. */
	List<EmailLog> findRecent(int limit);

	/** Remove um lote de linhas mais velhas que a janela de retencao. */
	int deleteOlderThan(java.time.Instant cutoff, int batchSize);

	/** Quantas linhas estao elegiveis agora, sem apagar nada (diagnostico e teste). */
	long countOlderThan(java.time.Instant cutoff);
}
