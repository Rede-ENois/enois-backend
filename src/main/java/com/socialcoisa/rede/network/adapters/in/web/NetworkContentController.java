package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.ContentReconcileService;
import com.socialcoisa.rede.network.application.ContentSyncService;
import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Conteúdo público versionado para nós de borda (fase 3, NODE_EDGE).
 *
 * <p>Credencial de nó ({@code X-Node-Id} + {@code X-Node-Key}), como as rotas de tarefa. Não há
 * sessão de usuário aqui e não pode haver: a borda entrega o que é igual para todo mundo. O que
 * depende de quem lê — "eu participo desta comunidade", "este perfil é meu" — continua sendo
 * respondido pelo Control Plane com a sessão da pessoa.</p>
 *
 * <p>O nó informa a versão que já tem ({@code ?version=}) e recebe {@code changed=false} quando está
 * em dia. É o pull que evita baixar de novo o que não mudou.</p>
 */
@RestController
@RequestMapping("/network/content")
class NetworkContentController {

	private final ContentSyncService content;
	private final ContentReconcileService reconcile;
	private final NodeKeyService keys;

	NetworkContentController(ContentSyncService content, ContentReconcileService reconcile, NodeKeyService keys) {
		this.content = content;
		this.reconcile = reconcile;
		this.keys = keys;
	}

	record ContentResponse(String kind, String contentId, long version, String contentHash, boolean changed,
			String body) {
	}

	record SyncedRequest(long sourceVersion, String contentHash) {
	}

	record LagView(String kind, long behind) {
	}

	record ReconcileRequest(List<Ref> snapshots) {
	}

	record Ref(String kind, String contentId) {
	}

	record DivergenceView(String kind, String contentId, long nodeVersion, Long sourceVersion, String reason) {
	}

	/**
	 * O conteúdo público na versão vigente. Tipo desconhecido é 404 antes de qualquer consulta: o nó
	 * que inventar um tipo não recebe "o que der".
	 */
	@GetMapping("/{kind}/{contentId}")
	ContentResponse get(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String kind,
			@PathVariable String contentId, @RequestParam(required = false) Long version) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		ContentKind parsed = ContentKind.parse(kind)
			.orElseThrow(() -> new ApiException(404, "CONTENT_KIND_UNKNOWN", "Tipo de conteudo desconhecido."));
		var served = content.serve(node, parsed, contentId, version);
		return new ContentResponse(served.kind().name(), served.contentId(), served.version(), served.contentHash(),
			served.changed(), served.body());
	}

	/** O nó confirma o que guardou: versão e hash do corpo. É o que a reconciliação compara depois. */
	@PostMapping("/{kind}/{contentId}/synced")
	StatusResponse synced(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String kind,
			@PathVariable String contentId, @RequestBody(required = false) SyncedRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		ContentKind parsed = ContentKind.parse(kind)
			.orElseThrow(() -> new ApiException(404, "CONTENT_KIND_UNKNOWN", "Tipo de conteudo desconhecido."));
		if (request == null) {
			throw new ApiException(400, "CONTENT_SYNC_REQUIRED", "Corpo da sincronizacao ausente.");
		}
		content.recordSynced(node, parsed, contentId, request.sourceVersion(), request.contentHash());
		return new StatusResponse(contentId, "SYNCED");
	}

	/** Quanto este nó está atrás, por tipo. Base do painel e do alerta de divergência. */
	@GetMapping("/lag")
	List<LagView> lag(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		return content.lag(node).stream().map(lag -> new LagView(lag.kind().name(), lag.behind())).toList();
	}

	/**
	 * Reconciliação: o nó manda o que diz ter e recebe o que divergiu. Divergência também vira
	 * auditoria {@code SYNC_DIVERGENCE}, que é o alerta do painel.
	 *
	 * <p>O nó só pode pedir a conferência do que ele mesmo declarou ter: o snapshot é lido pelo id
	 * autenticado, nunca pelo que o corpo da requisição disser. Um nó não confere o cache de outro.</p>
	 */
	@PostMapping("/reconcile")
	List<DivergenceView> reconcile(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestBody(required = false) ReconcileRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		if (request == null || request.snapshots() == null) {
			return List.of();
		}
		List<DivergenceView> divergences = new ArrayList<>();
		for (Ref ref : request.snapshots()) {
			ContentKind kind = ContentKind.parse(ref.kind())
				.orElseThrow(() -> new ApiException(404, "CONTENT_KIND_UNKNOWN", "Tipo de conteudo desconhecido."));
			reconcile.snapshot(node.id(), kind, ref.contentId())
				.flatMap(reconcile::check)
				.ifPresent(found -> divergences.add(new DivergenceView(found.kind().name(), found.contentId(),
					found.nodeVersion(), found.sourceVersion(), found.reason())));
		}
		return divergences;
	}

	record StatusResponse(String contentId, String status) {
	}

	record ChangeView(long seq, String kind, String contentId, long version, String contentHash, Instant changedAt) {
	}

	record ChangesResponse(boolean reset, long cursor, long nextSeq, boolean hasMore, List<ChangeView> changes) {
	}

	/**
	 * O feed de mudanças do conteúdo público, depois do cursor do nó.
	 *
	 * <p>É o que permite ao nó de borda descobrir o que revalidar sem perguntar agregado por
	 * agregado. Só ids, versão e hash: o corpo continua vindo de {@code GET /network/content/...},
	 * pelo mesmo caminho autorizado.</p>
	 *
	 * <p>Um cursor atrás da janela retida recebe {@code reset=true} e nenhuma mudança — o único caso
	 * em que a rota não diz o que mudou, e ela diz isso explicitamente. Responder "nada mudou" ali
	 * deixaria o nó servindo conteúdo velho para sempre achando que está em dia.</p>
	 */
	@GetMapping("/changes")
	ChangesResponse changes(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestParam(defaultValue = "0") long cursor, @RequestParam(defaultValue = "200") int limit) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		var feed = content.changes(node, cursor, limit);
		return new ChangesResponse(feed.reset(), feed.cursor(), feed.nextSeq(), feed.hasMore(),
			feed.changes().stream().map(change -> new ChangeView(change.seq(), change.kind().name(),
				change.contentId(), change.version(), change.contentHash(), change.changedAt())).toList());
	}
}
