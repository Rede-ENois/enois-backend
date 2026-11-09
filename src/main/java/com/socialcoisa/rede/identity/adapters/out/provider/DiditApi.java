package com.socialcoisa.rede.identity.adapters.out.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.DiditConfig;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente HTTP da Didit. Só o backend fala com a Didit: a chave de API nunca sai daqui, e nenhum
 * retorno do navegador é usado como prova.
 *
 * <p>A Didit não tem host separado de sandbox — o modo é da aplicação (a chave é que é de teste
 * ou de produção). Por isso o endereço é configurável, mas o mesmo caminho {@code /v3/...} vale
 * nos dois modos.</p>
 *
 * <p>Nenhum corpo de resposta é registrado em log: a resposta de decisão traz dados pessoais
 * (documento, selfie, endereço) que esta plataforma não guarda nem imprime.</p>
 */
@Component
class DiditApi {

	private static final Logger LOG = LoggerFactory.getLogger(DiditApi.class);

	private final DiditConfig config;
	private final ObjectMapper objectMapper;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	DiditApi(DiditConfig config, ObjectMapper objectMapper) {
		this.config = config;
		this.objectMapper = objectMapper;
	}

	/** Resposta de criação de sessão: apenas o id e a URL hospedada. */
	record CreatedSession(String sessionId, String url, String status) {
	}

	/**
	 * Cria a sessão. O {@code vendor_data} é o id interno do usuário — é ele que amarra a sessão
	 * à conta, e não o contrário.
	 */
	Optional<CreatedSession> createSession(String userId) {
		HttpRequest request = base("/v3/session/")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body(userId), StandardCharsets.UTF_8))
			.timeout(Duration.ofSeconds(20))
			.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				// 400 costuma ser crédito insuficiente ou workflow inválido; 401/403, credencial.
				// O corpo NÃO é registrado: pode conter detalhe de validação com dado pessoal.
				LOG.warn("Didit recusou a criacao de sessao com status {}", response.statusCode());
				return Optional.empty();
			}
			JsonNode payload = objectMapper.readTree(response.body());
			String sessionId = payload.path("session_id").asString("");
			String url = payload.path("url").asString("");
			String status = payload.path("status").asString("");
			if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(url)) {
				return Optional.empty();
			}
			return Optional.of(new CreatedSession(sessionId, url, status));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception failure) {
			LOG.warn("Didit indisponivel ao criar sessao: {}", failure.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/**
	 * Lê o desfecho no servidor da Didit. Esta é a única fonte de aprovação: o webhook só avisa
	 * que algo mudou.
	 */
	Optional<String> fetchStatus(String sessionId) {
		HttpRequest request = base("/v3/session/" + sessionId + "/decision/")
			.GET()
			.timeout(Duration.ofSeconds(15))
			.build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				LOG.warn("Didit recusou a leitura da sessao com status {}", response.statusCode());
				return Optional.empty();
			}
			JsonNode payload = objectMapper.readTree(response.body());
			String status = payload.path("status").asString("");
			return StringUtils.hasText(status) ? Optional.of(status) : Optional.empty();
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception failure) {
			LOG.warn("Didit indisponivel ao ler a sessao: {}", failure.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/** Saldo e recarga automática — a checagem que impede cobrança inesperada. */
	Optional<Balance> fetchBalance() {
		HttpRequest request = base("/v3/billing/balance/").GET().timeout(Duration.ofSeconds(15)).build();
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				return Optional.empty();
			}
			JsonNode payload = objectMapper.readTree(response.body());
			return Optional.of(new Balance(decimal(payload.path("balance").asString(null)),
				payload.path("auto_refill_enabled").asBoolean(false)));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (Exception failure) {
			return Optional.empty();
		}
	}

	record Balance(BigDecimal balanceUsd, boolean autoRefillEnabled) {
	}

	private HttpRequest.Builder base(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
			.header("x-api-key", config.apiKey())
			.header("Accept", "application/json");
		return builder;
	}

	private String body(String userId) {
		// Só os campos necessários: workflow e o id interno. Nada de dados pessoais no pedido —
		// eles são coletados pela própria Didit, na sessão dela.
		var payload = objectMapper.createObjectNode();
		payload.put("workflow_id", config.workflowId());
		payload.put("vendor_data", userId);
		return objectMapper.writeValueAsString(payload);
	}

	private static BigDecimal decimal(String value) {
		if (!StringUtils.hasText(value)) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(value);
		}
		catch (NumberFormatException malformed) {
			return BigDecimal.ZERO;
		}
	}
}