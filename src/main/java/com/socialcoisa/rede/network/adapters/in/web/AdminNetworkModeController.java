package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.NetworkModeService;
import com.socialcoisa.rede.network.application.port.out.NetworkStateRepository;
import com.socialcoisa.rede.network.domain.NetworkMode;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Modo operacional da rede no painel (docs/rede-distribuida/06-contingencia.md).
 *
 * <p>{@code DEGRADED} e {@code EMERGENCY} só existiam como enum: nenhum caminho os acionava, então a
 * contingência descrita no doc 06 era narrativa. Agora o admin declara o modo com motivo, a
 * transição é auditada, o nó recebe o modo no heartbeat e o despacho obedece: em {@code DEGRADED} e
 * {@code EMERGENCY} nada novo é designado, e em {@code RECOVERY} a fila volta pela metade.</p>
 *
 * <p>{@code NORMAL} e {@code HIGH_LOAD} não são acionáveis aqui de propósito: eles são os números
 * medidos. Um botão de "forçar NORMAL" com a fila cheia seria uma decisão desfeita na varredura
 * seguinte.</p>
 */
@RestController
@RequestMapping("/admin/network/mode")
class AdminNetworkModeController {

	private final NetworkModeService modes;
	private final NetworkStateRepository state;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkModeController(NetworkModeService modes, NetworkStateRepository state,
			CurrentUserResolver currentUserResolver, AdminGuard adminGuard) {
		this.modes = modes;
		this.state = state;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record ModeView(String mode, String reason, Instant since, boolean acceptsNewTasks, boolean manual) {
	}

	/** Declaração humana: precisa do modo e do motivo, que vão para a auditoria. */
	record DeclareRequest(String mode, String reason) {
	}

	@GetMapping
	ModeView current(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		NetworkMode mode = modes.current();
		return state.current()
			.map(record -> new ModeView(mode.name(), record.reason(), record.since(), mode.dispatchAllowed(),
				mode.manual()))
			.orElse(new ModeView(mode.name(), null, null, mode.dispatchAllowed(), mode.manual()));
	}

	@PostMapping
	ModeView declare(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) DeclareRequest request) {
		CurrentUser admin = admin(sessionToken);
		if (request == null) {
			throw new ApiException(400, "NETWORK_MODE_REQUIRED", "Informe o modo a declarar.");
		}
		NetworkMode mode;
		try {
			mode = NetworkMode.valueOf(request.mode() == null ? "" : request.mode().trim().toUpperCase());
		}
		catch (IllegalArgumentException unknown) {
			throw new ApiException(400, "NETWORK_MODE_UNKNOWN", "Modo operacional desconhecido.");
		}
		NetworkMode applied = modes.declare(admin.id().value(), mode, request.reason());
		return state.current()
			.map(record -> new ModeView(applied.name(), record.reason(), record.since(), applied.dispatchAllowed(),
				applied.manual()))
			.orElse(new ModeView(applied.name(), null, null, applied.dispatchAllowed(), applied.manual()));
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}
