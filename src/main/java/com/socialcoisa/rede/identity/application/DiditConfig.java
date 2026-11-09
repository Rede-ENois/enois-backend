package com.socialcoisa.rede.identity.application;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Configuracao da integracao com a Didit.
 *
 * <p>Os segredos vivem EXCLUSIVAMENTE no ambiente do backend: a chave de API e o segredo do
 * webhook nunca chegam ao front, nem sao gravados em tabela que a administracao possa ler. Sem
 * eles a integracao fica desligada (fail-closed) e a verificacao aparece como indisponivel.</p>
 *
 * <p>Os limites financeiros existem para que uma falha de configuracao nao vire cobranca: com
 * {@code rede.identity.didit.auto-topup-enabled=false} a integracao se recusa a rodar quando o
 * console mostra recarga automatica ligada, e o teto mensal de verificacoes e o teto de gasto em
 * dolares param a criacao de sessoes antes de o credito acabar.</p>
 */
@Component
public class DiditConfig {

	private final String apiKey;
	private final String workflowId;
	private final String baseUrl;
	private final String webhookSecret;
	private final int monthlyVerificationLimit;
	private final BigDecimal monthlyBudgetUsd;
	private final boolean autoTopupEnabled;
	private final BigDecimal pricePerVerificationUsd;

	public DiditConfig(
			@Value("${rede.identity.didit.api-key:}") String apiKey,
			@Value("${rede.identity.didit.workflow-id:}") String workflowId,
			@Value("${rede.identity.didit.base-url:https://verification.didit.me}") String baseUrl,
			@Value("${rede.identity.didit.webhook-secret:}") String webhookSecret,
			@Value("${rede.identity.didit.monthly-verification-limit:25}") int monthlyVerificationLimit,
			@Value("${rede.identity.didit.monthly-budget-usd:10.00}") BigDecimal monthlyBudgetUsd,
			@Value("${rede.identity.didit.auto-topup-enabled:false}") boolean autoTopupEnabled,
			@Value("${rede.identity.didit.price-per-verification-usd:0.33}") BigDecimal pricePerVerificationUsd) {
		this.apiKey = apiKey;
		this.workflowId = workflowId;
		String trimmed = baseUrl == null ? "" : baseUrl.trim();
		this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
		this.webhookSecret = webhookSecret;
		this.monthlyVerificationLimit = monthlyVerificationLimit;
		this.monthlyBudgetUsd = monthlyBudgetUsd == null ? BigDecimal.ZERO : monthlyBudgetUsd;
		this.autoTopupEnabled = autoTopupEnabled;
		this.pricePerVerificationUsd = pricePerVerificationUsd == null ? BigDecimal.ZERO : pricePerVerificationUsd;
	}

	public String apiKey() {
		return apiKey;
	}

	public String workflowId() {
		return workflowId;
	}

	public String baseUrl() {
		return baseUrl;
	}

	public String webhookSecret() {
		return webhookSecret;
	}

	public int monthlyVerificationLimit() {
		return monthlyVerificationLimit;
	}

	public BigDecimal monthlyBudgetUsd() {
		return monthlyBudgetUsd;
	}

	public boolean autoTopupEnabled() {
		return autoTopupEnabled;
	}

	public BigDecimal pricePerVerificationUsd() {
		return pricePerVerificationUsd;
	}

	/** Sessao e webhook precisam dos dois segredos; qualquer ausencia desliga a integracao. */
	public boolean configured() {
		return StringUtils.hasText(apiKey) && StringUtils.hasText(workflowId);
	}

	public boolean webhookConfigured() {
		return StringUtils.hasText(webhookSecret);
	}

	/**
	 * Recarga automatica ligada na conta Didit. O projeto proibe cobranca inesperada, entao com
	 * esta chave ligada nenhuma sessao e criada — a verificacao fica pausada ate desligarem a
	 * recarga no console e refletirem isso aqui.
	 */
	public void requireNoUnexpectedCharge() {
		if (autoTopupEnabled) {
			throw new ApiException(503, "IDENTITY_BILLING_LOCKED",
				"A verificacao de identidade esta pausada enquanto a cobranca automatica estiver ligada.");
		}
	}
}
