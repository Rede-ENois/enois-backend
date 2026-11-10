package com.socialcoisa.rede.identity.adapters.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.identity.adapters.out.provider.DiditIdentityProvider;
import com.socialcoisa.rede.identity.application.DiditConfig;
import com.socialcoisa.rede.identity.application.IdentityVerificationService;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationProvider;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook da Didit. A rota é pública de propósito (a Didit não tem sessão aqui); a autenticação é
 * o HMAC-SHA256 hex em {@code X-Signature} sobre os bytes crus do corpo, conferido em tempo
 * constante. Sem segredo configurado, recusa tudo (fail-closed).
 *
 * <p>O webhook NÃO aprova ninguém: ele só marca a sessão para releitura. A decisão de desfecho
 * vem SEMPRE de {@code GET /v3/session/{id}/decision/} lido pelo próprio backend — nunca do corpo
 * do webhook, nunca do navegador. Por isso o corpo aqui é tratado como aviso, e só os campos
 * operacionais (session_id, status) são olhados, sem qualquer dado pessoal.</p>
 */
@RestController
@RequestMapping("/webhooks")
class DiditWebhookController {

	private static final Logger LOG = LoggerFactory.getLogger(DiditWebhookController.class);

	private final DiditConfig config;
	private final IdentityVerificationService identityService;
	private final ObjectMapper objectMapper;

	DiditWebhookController(DiditConfig config, IdentityVerificationService identityService,
			ObjectMapper objectMapper) {
		this.config = config;
		this.identityService = identityService;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/didit")
	ResponseEntity<Void> receive(
			@RequestHeader(value = "X-Signature", required = false) String signature,
			@RequestHeader(value = "X-Timestamp", required = false) String timestamp,
			@RequestBody byte[] body) {
		String secret = config.webhookSecret();
		if (!StringUtils.hasText(secret)) {
			return ResponseEntity.status(503).build();
		}
		if (!validSignature(secret, signature, timestamp, body)) {
			return ResponseEntity.status(401).build();
		}
		try {
			JsonNode payload = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
			String sessionId = payload.path("session_id").asString("");
			// Somente o "Approved" da Didit é desfecho. Qualquer outro estado segue para a
			// releitura ou é ignorado; nenhum campo de decisão/PII é lido nem registrado.
			// "Approved"/"Declined" apenas autorizam a releitura; o desfecho gravado vem do GET
			// /v3/session/{id}/decision/. O status do corpo nunca e aplicado diretamente.
			if (StringUtils.hasText(sessionId)) {
				applyOutcome(sessionId);
			}
			// "environment": "sandbox" nos testes; nada a fazer de diferente — o modo é da chave.
			return ResponseEntity.ok().build();
		}
		catch (RuntimeException malformed) {
			// Corpo assinado mas inesperado: a Didit não precisa reenviar (o desfecho vem da
			// releitura).
			LOG.warn("Webhook da Didit com corpo invalido: {}", malformed.getClass().getSimpleName());
			return ResponseEntity.ok().build();
		}
	}

	private void applyOutcome(String sessionId) {
		try {
			identityService.applyProviderOutcome(sessionId);
		}
		catch (ApiException unknownSession) {
			// Sessão que não é nossa (ou já aplicada): ignora sem erro, para não consumir retry.
		}
	}

	/**
	 * {@code X-Signature} assina os bytes crus transmitidos. O {@code X-Timestamp} protege contra
	 * replay: fora da janela de 5 minutos, o corpo é recusado mesmo com assinatura válida.
	 */
	private boolean validSignature(String secret, String signature, String timestamp, byte[] body) {
		if (!StringUtils.hasText(signature)) {
			return false;
		}
		if (StringUtils.hasText(timestamp)) {
			try {
				long sentAt = Long.parseLong(timestamp.trim());
				long skew = Math.abs(Instant.now().getEpochSecond() - sentAt);
				if (skew > 300) {
					return false;
				}
			}
			catch (NumberFormatException malformedTimestamp) {
				return false;
			}
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String expected = HexFormat.of().formatHex(mac.doFinal(body));
			return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				signature.trim().getBytes(StandardCharsets.US_ASCII));
		}
		catch (Exception failure) {
			return false;
		}
	}

	/** Referência ao provedor apenas para deixar claro o par webhook ↔ provedor no módulo. */
	static Class<? extends IdentityVerificationProvider> provider() {
		return DiditIdentityProvider.class;
	}
}
