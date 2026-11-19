package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Duration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.StorageOperationMetrics;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Operações por segundo e latência do Storage — os dois indicadores do §17 do brief que o painel não
 * tinha porque ninguém contava operação (V366).
 *
 * <p>Rota de admin, junto do restante do painel de Storage: a série mostra o ritmo do serviço inteiro
 * (todos os buckets), não o de uma conta. Um participante vendo a taxa global saberia quanto a
 * instalação atende — informação de operador, não de cliente.</p>
 */
@RestController
@RequestMapping("/admin/network/storage")
class AdminNetworkStorageMetricsController {

	/** Janela máxima: mais que um dia de minuto a minuto não cabe numa tela nem numa leitura. */
	private static final Duration JANELA_MAXIMA = Duration.ofDays(1);

	private final StorageOperationMetrics metrics;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkStorageMetricsController(StorageOperationMetrics metrics, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.metrics = metrics;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	/**
	 * O agregado da janela. {@code minutes} padrão 15, teto de 1440 (um dia).
	 *
	 * <p>Janela acima do teto é recusada, não truncada: quem pediu 7 dias e recebeu 1 acha que a
	 * instalação ficou sem operação por 6 dias.</p>
	 */
	@GetMapping("/operations")
	StorageOperationMetrics.Report operations(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "15") long minutes) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		if (minutes < 1 || minutes > JANELA_MAXIMA.toMinutes()) {
			throw new ApiException(400, "INVALID_ARGUMENT",
				"Janela fora do intervalo aceito (1 a " + JANELA_MAXIMA.toMinutes() + " minutos).");
		}
		return metrics.report(Duration.ofMinutes(minutes));
	}
}