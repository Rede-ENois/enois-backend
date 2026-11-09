package com.socialcoisa.rede.moderation.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.security.EnumerationGuardFilter;
import com.socialcoisa.rede.shared.security.EnumerationRiskEngine;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Painel da proteção contra enumeração: o que está sob observação, o que está bloqueado e por quê.
 *
 * <p>Só admin vê. A tela mostra o estado por chave (IP ou identidade), o nível, a pontuação e o
 * tempo que falta de bloqueio — e permite liberar um bloqueio sem reiniciar o processo.</p>
 *
 * <p>No modo <em>shadow</em> (padrão) a lista mostra as chaves sob observação com a pontuação, e
 * nenhum bloqueio existe: é assim que o time valida os limiares com tráfego real antes de ligar o
 * bloqueio automático.</p>
 */
@RestController
class EnumerationAdminController {

	private final AdminGuard adminGuard;
	private final CurrentUserResolver currentUserResolver;
	private final EnumerationRiskEngine engine;
	private final EnumerationGuardFilter filter;

	EnumerationAdminController(AdminGuard adminGuard, CurrentUserResolver currentUserResolver,
			EnumerationRiskEngine engine, EnumerationGuardFilter filter) {
		this.adminGuard = adminGuard;
		this.currentUserResolver = currentUserResolver;
		this.engine = engine;
		this.filter = filter;
	}

	/** Estado geral: o modo em que a proteção está e quantas chaves estão sob observação. */
	@GetMapping("/admin/enumeration/status")
	Map<String, Object> status(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return Map.of(
			"mode", filter.mode().name(),
			"trackedKeys", engine.trackedKeys(),
			"activeBlocks", filter.activeBlocks().size());
	}

	/** Os bloqueios ativos, com o tempo que falta de cada um. */
	@GetMapping("/admin/enumeration/blocks")
	List<BlockView> blocks(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return filter.activeBlocks().entrySet().stream()
			.map(entry -> new BlockView(entry.getKey(), entry.getValue()))
			.toList();
	}

	/** Libera um bloqueio antes do prazo (a pessoa recorrente não espera o fim). */
	@PostMapping("/admin/enumeration/blocks/{key}/release")
	Map<String, Object> release(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String key) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		boolean released = filter.release(key);
		engine.reset(key);
		return Map.of("key", key, "released", released);
	}

	/** As chaves observadas com a pontuação atual — é o que o time revisa no modo shadow. */
	@GetMapping("/admin/enumeration/observed")
	List<ObservedView> observed(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return engine.observed(Math.max(1, Math.min(limit, 200))).stream()
			.map(entry -> new ObservedView(entry.key(), entry.assessment().level().name(),
				entry.assessment().score(), entry.assessment().reasons()))
			.toList();
	}

	record BlockView(String key, long remainingMs) {
	}

	record ObservedView(String key, String level, int score, List<String> reasons) {
	}
}
