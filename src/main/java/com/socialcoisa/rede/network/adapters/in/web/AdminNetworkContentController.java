package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.ContentReconcileService;
import com.socialcoisa.rede.network.application.ContentReconcileWorker;
import com.socialcoisa.rede.network.application.ContentSyncService;
import com.socialcoisa.rede.network.application.NetworkNodeService;
import com.socialcoisa.rede.network.application.NetworkReplicationCoverageService;
import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Reconciliação de conteúdo no painel. Admin, como o resto de {@code /admin/network}.
 *
 * <p>A tela responde duas perguntas que a auditoria sozinha não responde: <b>quais</b> nós estão
 * divergentes agora (a auditoria é histórico, não estado) e <b>desde quando</b>. Sem a segunda, um
 * nó que divergiu há dez minutos e um que divergiu há três dias aparecem iguais.</p>
 */
@RestController
@RequestMapping("/admin/network/content")
class AdminNetworkContentController {

	private final ContentReconcileService reconcile;
	private final ContentReconcileWorker worker;
	private final NetworkReplicationCoverageService coverage;
	private final ContentSyncService content;
	private final NetworkNodeService nodes;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkContentController(ContentReconcileService reconcile, ContentReconcileWorker worker,
			NetworkReplicationCoverageService coverage, ContentSyncService content, NetworkNodeService nodes,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.reconcile = reconcile;
		this.worker = worker;
		this.coverage = coverage;
		this.content = content;
		this.nodes = nodes;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record DivergenceView(String nodeId, String kind, String contentId, long nodeVersion, String reason,
			Instant divergenceAt, Instant checkedAt) {

		static DivergenceView of(ContentVersionRepository.NodeSnapshot snapshot) {
			return new DivergenceView(snapshot.nodeId(), snapshot.kind().name(), snapshot.contentId(),
				snapshot.sourceVersion(), snapshot.divergenceReason(), snapshot.divergenceAt(), snapshot.checkedAt());
		}
	}

	record ReconcileOverview(long snapshots, long divergent) {
	}

	/**
	 * Uma linha por tipo de conteúdo: em quantas regiões ele tem cópia utilizável, quantas regiões
	 * a rede tem para comparar e quantos nós confirmaram tê-lo.
	 *
	 * <p>É a resposta a "a rede sobrevive à queda de uma região?" — a pergunta que a contagem por nó
	 * nunca respondeu, porque dez nós no mesmo rack são dez cópias e uma região.</p>
	 */
	record CoverageView(String kind, long regionsWithCopy, long totalRegions, long copies) {

		static CoverageView of(ContentVersionRepository.RegionCoverage row) {
			return new CoverageView(row.kind().name(), row.regionsWithCopy(), row.totalRegions(), row.copies());
		}
	}

	@GetMapping("/divergences")
	List<DivergenceView> divergences(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "200") int limit) {
		admin(sessionToken);
		return reconcile.divergent(limit).stream().map(DivergenceView::of).toList();
	}

	@GetMapping("/overview")
	ReconcileOverview overview(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return new ReconcileOverview(reconcile.countAll(), reconcile.countDivergent());
	}

	/**
	 * Cobertura geográfica por tipo de conteúdo. Nó de região fora de operação não conta para
	 * cobertura: o cache dele pode estar completo, mas ele não atende ninguém enquanto a região
	 * estiver declarada fora — contá-lo prometeria contingência que a rede não tem.
	 */
	@GetMapping("/coverage")
	List<CoverageView> coverage(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return coverage.coverage().stream().map(CoverageView::of).toList();
	}

	/**
	 * O atraso de sincronização <b>deste</b> nó, por tipo de conteúdo (brief §8.6, histórico de
	 * sincronização).
	 *
	 * <p>O nó já podia perguntar o próprio atraso ({@code GET /network/content/lag}, autenticado pela
	 * chave dele); o administrador não via o de ninguém. Sem esta rota, "a réplica X está atrasada"
	 * só era sabível entrando na máquina — e o operador que precisa redistribuir carga não entra em
	 * vinte máquinas para descobrir qual está para trás.</p>
	 *
	 * <p>Nó desconhecido é 404, não lista zerada: "não está atrasado" e "não existe" não podem ser a
	 * mesma resposta. Os tipos vêm todos, inclusive os que estão em dia: o tipo ausente esconderia
	 * qual conjunto foi medido.</p>
	 */
	@GetMapping("/nodes/{nodeId}/lag")
	List<LagView> nodeLag(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String nodeId) {
		admin(sessionToken);
		return content.lag(nodes.require(nodeId)).stream()
			.map(lag -> new LagView(lag.kind().name(), lag.behind()))
			.toList();
	}

	record LagView(String kind, long behind) {
	}

	/**
	 * Roda uma rodada da varredura agora. Existe para quem acabou de corrigir uma origem e quer ver
	 * o alerta fechar sem esperar o próximo ciclo — a varredura periódica continua sendo a que
	 * garante a cobertura.
	 */
	@PostMapping("/check")
	ReconcileOverview check(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		worker.runOnce();
		return new ReconcileOverview(reconcile.countAll(), reconcile.countDivergent());
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}
