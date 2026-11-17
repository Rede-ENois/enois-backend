package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkRegionService;
import com.socialcoisa.rede.network.application.port.out.NetworkRegionRepository;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Recuperação regional no painel: a região que sai de operação e a que volta.
 *
 * <p>"Região afetada sai; nós de outras regiões atendem o que podem" (fase 6) não tinha rota: a
 * região era só afinidade de ordenação, e o admin não tinha como dizer ao Control Plane que uma
 * região estava em desastre. Declarar aqui fecha as quatro portas de uma vez — o despacho não
 * escolhe o nó, a capacidade não conta a vaga, o heartbeat responde {@code acceptTasks=false} e o
 * que estava na mão dos nós da região volta à fila na rodada seguinte do scheduler.</p>
 *
 * <p>Como toda rota de {@code /admin}, passa pela trava central e ainda pelo {@link AdminGuard}; a
 * declaração exige motivo, que vai para a auditoria com o ator. Tirar uma região do ar é decisão de
 * incidente, não configuração — o motivo é o que sobra para quem investiga depois.</p>
 */
@RestController
@RequestMapping("/admin/network/regions")
class AdminNetworkRegionController {

	private final NetworkRegionService regions;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkRegionController(NetworkRegionService regions, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.regions = regions;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	/**
	 * {@code outOfService} é a região como o evacuador a vê (normalizada); {@code since} é quando ela
	 * ENTROU na contingência — redeclarar não reinicia o incidente.
	 */
	record RegionView(String region, String declaredBy, String reason, Instant since) {

		static RegionView of(NetworkRegionRepository.RegionRecord record) {
			return new RegionView(record.region(), record.actorId(), record.reason(), record.since());
		}
	}

	/** Declaração humana: precisa da região e do motivo, que vão para a auditoria. */
	record DeclareRequest(String region, String reason) {
	}

	@GetMapping
	List<RegionView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return regions.outOfService()
			.stream()
			.map(region -> regions.find(region).map(RegionView::of).orElse(null))
			.filter(java.util.Objects::nonNull)
			.toList();
	}

	@PostMapping
	List<RegionView> declare(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) DeclareRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null) {
			throw new ApiException(400, "NETWORK_REGION_REQUIRED", "Informe a região a tirar de operação.");
		}
		regions.declare(admin.id().value(), request.region(), request.reason());
		return list(sessionToken);
	}

	@DeleteMapping("/{region}")
	List<RegionView> clear(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String region) {
		CurrentUser admin = admin(sessionToken);
		regions.clear(admin.id().value(), region);
		return list(sessionToken);
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}