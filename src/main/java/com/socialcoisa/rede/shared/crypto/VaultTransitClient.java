package com.socialcoisa.rede.shared.crypto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente do motor <em>Transit</em> de um cofre (OpenBao/Vault) — a chave-mestra mora lá, o app só
 * pede "embrulhe" e "desembrulhe".
 *
 * <p>Decisão de arquitetura de 2026-10-27 (§15.1): cofre próprio, na infraestrutura do time. O que isso
 * protege: banco, backup e dump deixam de ter material legível, e a chave-mestra não fica no ambiente
 * do app. O que <strong>não</strong> protege: quem tem root no host e deslacra o cofre.</p>
 *
 * <p>Regras desta classe: o token nunca é logado, nem o texto claro; toda falha vira
 * {@link CryptoException} com código próprio, porque "deu erro no KMS" não diz o que fazer — e um
 * decifra falhando silenciosamente é pior do que um erro.</p>
 */
public final class VaultTransitClient {

	private final URI baseAddress;
	private final String token;
	private final String transitKey;
	private final Duration timeout;
	private final ObjectMapper mapper;
	private final HttpClient http;

	public VaultTransitClient(URI baseAddress, String token, String transitKey, Duration timeout,
			ObjectMapper mapper) {
		if (baseAddress == null) {
			throw new CryptoException("VAULT_CONFIG_INVALID", "Endereco do cofre obrigatorio.");
		}
		if (token == null || token.isBlank()) {
			throw new CryptoException("VAULT_CONFIG_INVALID", "Token do cofre obrigatorio.");
		}
		if (transitKey == null || transitKey.isBlank()) {
			throw new CryptoException("VAULT_CONFIG_INVALID", "Nome da chave de trânsito obrigatorio.");
		}
		this.baseAddress = baseAddress;
		this.token = token;
		this.transitKey = transitKey;
		this.timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
		this.mapper = mapper;
		this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
	}

	/** Desembrulha a DEK guardada (ciphertext do cofre) e devolve os bytes da chave. */
	public byte[] decrypt(String wrappedKey) {
		JsonNode response = post("/v1/transit/decrypt/" + transitKey, Map.of("ciphertext", wrappedKey));
		String plaintext = response.path("data").path("plaintext").asString(null);
		if (plaintext == null || plaintext.isBlank()) {
			throw new CryptoException("VAULT_BAD_RESPONSE", "O cofre nao devolveu a chave embrulhada.");
		}
		try {
			return Base64.getDecoder().decode(plaintext);
		}
		catch (IllegalArgumentException failure) {
			throw new CryptoException("VAULT_BAD_RESPONSE", "A chave devolvida pelo cofre nao e base64.", failure);
		}
	}

	/** Embrulha a DEK e devolve o ciphertext do cofre (é isso que vai para o banco). */
	public String encrypt(byte[] key) {
		if (key == null || key.length == 0) {
			throw new CryptoException("KEY_INVALID", "Nao ha chave para embrulhar.");
		}
		String plaintext = Base64.getEncoder().encodeToString(key);
		JsonNode response = post("/v1/transit/encrypt/" + transitKey, Map.of("plaintext", plaintext));
		String ciphertext = response.path("data").path("ciphertext").asString(null);
		if (ciphertext == null || ciphertext.isBlank()) {
			throw new CryptoException("VAULT_BAD_RESPONSE", "O cofre nao devolveu a chave embrulhada.");
		}
		return ciphertext;
	}

	private JsonNode post(String path, Map<String, String> body) {
		HttpRequest request = HttpRequest.newBuilder(baseAddress.resolve(path))
			.timeout(timeout)
			.header("X-Vault-Token", token)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(toJson(body), StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new CryptoException("VAULT_UNAVAILABLE", "A chamada ao cofre foi interrompida.");
		}
		catch (java.io.IOException failure) {
			throw new CryptoException("VAULT_UNAVAILABLE", "O cofre nao respondeu.", failure);
		}
		int status = response.statusCode();
		if (status == 200 || status == 204) {
			return readJson(response.body());
		}
		if (status == 403 || status == 401) {
			throw new CryptoException("VAULT_DENIED", "O cofre recusou a credencial do servico.");
		}
		if (status == 404) {
			throw new CryptoException("VAULT_KEY_NOT_FOUND", "Chave de trânsito nao existe no cofre.");
		}
		throw new CryptoException("VAULT_UNAVAILABLE", "O cofre respondeu " + status + ".");
	}

	private String toJson(Map<String, String> body) {
		try {
			return mapper.writeValueAsString(body);
		}
		catch (RuntimeException failure) {
			throw new CryptoException("VAULT_REQUEST_INVALID", "Nao foi possivel montar a chamada ao cofre.", failure);
		}
	}

	private JsonNode readJson(String body) {
		try {
			JsonNode node = mapper.readTree(body);
			return node == null ? mapper.createObjectNode() : node;
		}
		catch (RuntimeException failure) {
			throw new CryptoException("VAULT_BAD_RESPONSE", "Resposta do cofre ilegivel.", failure);
		}
	}
}
