package com.socialcoisa.rede.shared.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints operacionais.
 *
 * O antigo {@code GET /openapi.json} foi removido: era um stub manual que ficava publico (o gateway
 * expoe o backend em /api/*) e enumerava a superficie da API. A documentacao real e gerada pelo
 * springdoc e so e habilitada com SPRINGDOC_ENABLED=true.
 */
@RestController
class OperationalController {

	@GetMapping("/health")
	Map<String, String> health() {
		return Map.of("status", "UP");
	}
}