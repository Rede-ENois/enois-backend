package com.socialcoisa.rede.network.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkRegionRepository;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Reconciliação do conteúdo de borda (fase 3): compara o que o nó diz ter com a origem.
 *
 * <p>Há dois caminhos que conferem o mesmo snapshot: o nó, quando chama {@code POST
 * /network/content/reconcile}, e a varredura periódica do Control Plane. Os dois passam por aqui,
 * para o painel não ter duas verdades sobre o mesmo nó.</p>
 *
 * <p><b>Por que a conferência de um item não é uma transação só.</b> Ler a origem pode falhar de
 * dois jeitos: o agregado sumiu (404 — perfil banido, comunidade excluída) ou a origem não
 * respondeu. Quando a leitura falha dentro de uma transação, o Spring marca essa transação como
 * rollback-only; gravar a divergência na mesma transação faria o commit explodir com
 * {@code UnexpectedRollbackException} e o nó receberia 500 em vez do alerta. Por isso a leitura tem
 * a própria transação (em {@link ContentSyncService#publish}) e a gravação tem a sua (em
 * {@link ContentDivergenceRecorder}); esta classe só orquestra, sem transação própria.</p>
 */
@Service
public class ContentReconcileService {

	/** O nó tem uma versão anterior do corpo; a origem já serve outra. */
	public static final String STALE = "CONTENT_STALE";

	/** O agregado sumiu da origem (perfil banido, comunidade excluída): o nó serve o que não existe. */
	public static final String GONE = "CONTENT_GONE";

	/**
	 * A origem não pôde ser lida por um motivo que não é "não existe" (erro de banco, defeito de
	 * serialização). Não é divergência do nó, mas também não é "em dia": fica registrado para o
	 * painel mostrar que aquele agregado não está sendo conferido.
	 */
	public static final String UNREADABLE = "CONTENT_UNREADABLE";

	private final ContentVersionRepository versions;
	private final ContentSyncService content;
	private final ContentDivergenceRecorder recorder;
	private final NetworkRegionRepository regions;

	ContentReconcileService(ContentVersionRepository versions, ContentSyncService content,
			ContentDivergenceRecorder recorder, NetworkRegionRepository regions) {
		this.versions = versions;
		this.content = content;
		this.recorder = recorder;
		this.regions = regions;
	}

	/**
	 * A fila da varredura: nunca conferidos primeiro, depois os conferidos há mais tempo.
	 *
	 * <p>Nó de região fora de operação (V304) fica fora: ele não recebe conteúdo desde a declaração
	 * — é a rede que parou de lhe servir, não o cache dele que divergiu. Conferir agora acusaria o nó
	 * por uma decisão do Control Plane, e quando a região voltar o nó sincroniza de novo e a fila o
	 * alcança sozinha.</p>
	 */
	@Transactional(readOnly = true)
	public List<ContentVersionRepository.NodeSnapshot> due(int limit) {
		return versions.dueForCheck(regions.outOfService(), limit);
	}

	/**
	 * Confere um snapshot contra a origem e grava o desfecho.
	 *
	 * <p>Sem {@code @Transactional} de propósito: a leitura e a gravação precisam de transações
	 * separadas, porque a leitura pode falhar e derrubar a dela.</p>
	 *
	 * @return a divergência encontrada; vazio quando o nó está em dia
	 */
	public Optional<Divergence> check(ContentVersionRepository.NodeSnapshot snapshot) {
		ContentVersion current;
		try {
			current = content.publish(snapshot.kind(), snapshot.contentId());
		}
		catch (RuntimeException unreadable) {
			String reason = reasonFor(unreadable);
			recorder.record(snapshot, reason, null);
			return Optional.of(new Divergence(snapshot.kind(), snapshot.contentId(), snapshot.sourceVersion(), null,
				reason));
		}
		// Compara o HASH e não a versão: versão igual com corpo diferente seria o pior caso, porque
		// o nó acharia que está em dia. O hash é do corpo servido, então é o que decide.
		if (current.contentHash().equals(snapshot.contentHash())) {
			recorder.record(snapshot, null, current.version());
			return Optional.empty();
		}
		recorder.record(snapshot, STALE, current.version());
		return Optional.of(new Divergence(snapshot.kind(), snapshot.contentId(), snapshot.sourceVersion(),
			current.version(), STALE));
	}

	/** O snapshot que o nó declarou ter, se ele declarou algum. */
	@Transactional(readOnly = true)
	public Optional<ContentVersionRepository.NodeSnapshot> snapshot(String nodeId, ContentKind kind,
			String contentId) {
		return versions.snapshot(nodeId, kind, contentId);
	}

	/** O que o painel lista: snapshots divergentes agora, do mais recente para o mais antigo. */
	@Transactional(readOnly = true)
	public List<ContentVersionRepository.NodeSnapshot> divergent(int limit) {
		return versions.divergent(limit);
	}

	@Transactional(readOnly = true)
	public long countDivergent() {
		return versions.countDivergent();
	}

	@Transactional(readOnly = true)
	public long countAll() {
		return versions.countAll();
	}

	/**
	 * Classifica a falha de leitura da origem. 404 é o agregado que não existe mais; qualquer outra
	 * coisa é a origem que não respondeu, e chamar isso de "sumiu" faria o painel acusar o nó por um
	 * problema do Control Plane.
	 */
	static String reasonFor(Throwable failure) {
		if (failure instanceof ApiException api && api.status() == 404) {
			return GONE;
		}
		return UNREADABLE;
	}

	/** Divergência encontrada: o nó está atrás, servindo o que não existe, ou a origem não respondeu. */
	public record Divergence(ContentKind kind, String contentId, long nodeVersion, Long sourceVersion,
			String reason) {
	}
}
