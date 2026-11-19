package com.socialcoisa.rede.shared.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints operacionais.
 *
 * O antigo {@code GET /openapi.json} foi removido: era um stub manual que ficava publico (o gateway
 * expoe o backend em /api/*) e enumerava a superficie da API.
 *
 * <p><strong>Esta API nao publica documento OpenAPI.</strong> O springdoc saiu do {@code pom.xml} no
 * lote de seguranca (incompativel com o Boot 4, e a UI dele expunha a superficie inteira da API);
 * nao existe endpoint {@code /openapi.json} nem variavel {@code SPRINGDOC_ENABLED} que o ligue —
 * quem pedir esse caminho recebe a resposta padronizada de "caminho fora da superficie" (200 sem
 * corpo), e nao um documento.
 *
 * <p>O contrato legivel da API e a pagina {@code /developers} do app; o contrato legivel por
 * maquina e o indice {@code GET /api/v1}, montado a partir do roteador de verdade. Se um dia houver
 * OpenAPI de novo, tem de ser por documento gerado e revisado — nao por um endpoint ligado por
 * variavel de ambiente em producao.
 */
@RestController
public class OperationalController {

	@GetMapping("/health")
	Map<String, String> health() {
		return Map.of("status", "UP");
	}
}
