package com.socialcoisa.rede.network.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.application.ReplicaCatalogService;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.ReplicaCursor;
import com.socialcoisa.rede.network.domain.ReplicaPage;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Catálogo da réplica de leitura (fase 3, {@code NODE_READ_REPLICA}).
 *
 * <p>Borda e réplica usam a mesma rota de conteúdo para ler um item; o que a réplica precisa e a
 * borda não é <b>saber o que existe</b>. A borda responde a "me dá o perfil @ana" e nunca pergunta
 * "quem são todos"; a réplica não tem como servir sem essa lista, então ela ganha uma rota só sua,
 * autenticada pela chave do nó.</p>
 *
 * <p>A resposta é ids, não corpos: o corpo de cada id continua vindo de {@code
 * GET /network/content/{kind}/{id}}, com a versão vigente e o hash do corpo. Assim a réplica não tem
 * um segundo caminho de serialização para a mesma projeção — e o que ela confirma em {@code /synced}
 * é exatamente o que a borda confirmaria.</p>
 *
 * <p>A autorização é por perfil, não por chave: nó ativo com {@code NODE_READ_REPLICA} ou {@code
 * NODE_LOCAL}. {@code NODE_EDGE} autentica e recebe 403 — a lista de todo mundo não é o que ele
 * precisa operar.</p>
 */
@RestController
@RequestMapping("/network/replica")
class NetworkReplicaController {

	private final ReplicaCatalogService catalog;
	private final NodeKeyService keys;

	NetworkReplicaController(ReplicaCatalogService catalog, NodeKeyService keys) {
		this.catalog = catalog;
		this.keys = keys;
	}

	/**
	 * Uma página do catálogo.
	 *
	 * @param afterAt chave do último item lido; omitido começa do início
	 * @param afterId id do último item lido, que desempata a chave
	 */
	record ReplicaPageResponse(String kind, List<String> items, boolean hasMore, String nextAfterAt,
			String nextAfterId) {
	}

	@GetMapping("/{kind}")
	ReplicaPageResponse page(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey, @PathVariable String kind,
			@RequestParam(required = false) String afterAt, @RequestParam(required = false) String afterId,
			@RequestParam(required = false, defaultValue = "100") int limit) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		// Tipo fora da allowlist é recusado antes de qualquer consulta: a réplica não descobre o
		// catálogo tentando "o que der".
		ContentKind parsed = ContentKind.parse(kind)
			.orElseThrow(() -> new ApiException(404, "CONTENT_KIND_UNKNOWN", "Tipo de conteudo desconhecido."));
		// Os dois lados do cursor andam juntos: só a chave, sem o id, não define uma posição única, e
		// só o id, sem a chave, não ordena. Um sem o outro é cursor corrompido, não "começa do início".
		boolean hasAt = afterAt != null && !afterAt.isBlank();
		boolean hasId = afterId != null && !afterId.isBlank();
		if (hasAt != hasId) {
			throw new ApiException(400, "REPLICA_CURSOR_INVALID", "Informe afterAt e afterId juntos.");
		}
		ReplicaCursor cursor = hasAt ? new ReplicaCursor(afterAt, afterId) : ReplicaCursor.start();
		ReplicaPage page = catalog.page(node, parsed, cursor, limit);
		return new ReplicaPageResponse(page.kind().name(), page.items(), page.hasMore(),
			page.nextCursor() == null ? null : page.nextCursor().key(),
			page.nextCursor() == null ? null : page.nextCursor().id());
	}
}