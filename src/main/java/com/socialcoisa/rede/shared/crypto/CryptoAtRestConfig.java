package com.socialcoisa.rede.shared.crypto;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

import tools.jackson.databind.ObjectMapper;

/**
 * Cifra em repouso, desligada por padrão.
 *
 * <p>Ligar é uma decisão de ambiente ({@code rede.crypto.at-rest.enabled=true}) e exige o cofre
 * configurado. Faltando endereço, token ou nome da chave, o boot falha com mensagem explícita: subir
 * com a cifra "ligada" e sem cofre faria a primeira gravação falhar em produção, longe do erro.</p>
 *
 * <p>O token do cofre vem do ambiente e nunca é logado, impresso em resposta ou versionado. O
 * runbook (unseal, rotação, break-glass) mora fora do repositório público.</p>
 */
@Configuration
@ConditionalOnProperty(name = "rede.crypto.at-rest.enabled", havingValue = "true")
class CryptoAtRestConfig {

	@Bean
	VaultTransitClient vaultTransitClient(@Value("${rede.crypto.vault.address:}") String address,
			@Value("${rede.crypto.vault.token:}") String token,
			@Value("${rede.crypto.vault.transit-key:}") String transitKey,
			@Value("${rede.crypto.vault.timeout-seconds:5}") long timeoutSeconds,
			ObjectMapper objectMapper) {
		if (!StringUtils.hasText(address) || !StringUtils.hasText(token) || !StringUtils.hasText(transitKey)) {
			throw new IllegalStateException(
				"Cifra em repouso ligada sem cofre configurado: defina rede.crypto.vault.address, "
					+ "rede.crypto.vault.token e rede.crypto.vault.transit-key (ou desligue "
					+ "rede.crypto.at-rest.enabled).");
		}
		return new VaultTransitClient(URI.create(address), token, transitKey,
			Duration.ofSeconds(Math.max(1, timeoutSeconds)), objectMapper);
	}

	@Bean
	WrappedKeyStore wrappedKeyStore(JdbcTemplate jdbc) {
		return new JdbcWrappedKeyStore(jdbc);
	}

	@Bean
	KeyProvider dataKeyProvider(WrappedKeyStore store, VaultTransitClient vault) {
		return new VaultBackedKeyProvider(store, vault);
	}

	@Bean
	DataKeyBootstrap dataKeyBootstrap(WrappedKeyStore store, VaultTransitClient vault) {
		return new DataKeyBootstrap(store, vault);
	}
}
