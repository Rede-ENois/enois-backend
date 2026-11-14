package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.MediaJobStatus;
import com.socialcoisa.rede.network.domain.MediaTranscodeJob;

/**
 * Estado dos transcodes delegados a nós {@code NODE_MEDIA} (V295).
 *
 * <p>As escritas são condicionais por estado: só uma tentativa pode marcar {@code VERIFIED}, e um
 * resultado rejeitado não sobrescreve um já verificado. Ler e depois gravar deixaria a janela em que
 * duas tentativas do mesmo nó (ou dois nós) verificam a mesma mídia.</p>
 */
public interface MediaJobRepository {

	Optional<MediaTranscodeJob> findByMediaId(String mediaId);

	Optional<MediaTranscodeJob> findByTaskId(String taskId);

	/** Cria a linha em PENDING. A PK é o mediaId: pedir de novo a mesma mídia não duplica o job. */
	void create(MediaTranscodeJob job);

	/**
	 * Grava o que o Control Plane MEDIU do arquivo entregue pelo nó. Não muda o status: ter o
	 * arquivo é diferente de ter conferido a declaração do nó, e o segundo passo pode não ter chegado.
	 */
	int markMeasured(String mediaId, long resultBytes, String resultSha256);

	/** Marca verificado. Condicional: só grava se a linha ainda não estiver VERIFIED. */
	int markVerified(String mediaId, long resultBytes, String resultSha256, Instant now);

	/** Marca rejeitado com o motivo. Condicional: não rebaixa um job já verificado. */
	int markRejected(String mediaId, String reason, Instant now);

	/** Guarda o motivo de uma falha retentável sem mudar o estado (a linha continua PENDING). */
	int recordFailure(String mediaId, String reason);

	/** Jobs recentes, para o painel. */
	List<MediaTranscodeJob> listRecent(int limit);

	long countByStatus(MediaJobStatus status);
}
