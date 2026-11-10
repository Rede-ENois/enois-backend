package com.socialcoisa.rede.identity.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Contabilidade de capacidade da verificação paga.
 *
 * <p>A regra do projeto é não presumir crédito grátis: o limite é conservador, o teto de gasto é
 * em dólar e a conta é feita em cima das sessões realmente iniciadas no mês. Quando o limite
 * estoura, novas liberações param — mas quem já foi liberado continua liberado e quem já está com
 * verificação em andamento continua podendo concluir.</p>
 *
 * <p>A contagem sai das próprias tentativas registradas ({@code identity_verifications} com
 * provedor {@code didit} iniciadas no mês corrente), então não existe segundo lugar onde o número
 * possa divergir.</p>
 */
@Service
public class DiditCapacityService {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	private final IdentityVerificationRepository verifications;
	private final DiditConfig config;
	private final Clock clock;

	public DiditCapacityService(IdentityVerificationRepository verifications, DiditConfig config, Clock clock) {
		this.verifications = verifications;
		this.config = config;
		this.clock = clock;
	}

	/** Início do mês corrente no fuso da plataforma. */
	public Instant cycleStart() {
		LocalDate today = LocalDate.now(clock.withZone(ZONE));
		return YearMonth.from(today).atDay(1).atStartOfDay(ZONE).toInstant();
	}

	public int startedThisCycle() {
		return verifications.countStartedSince("didit", cycleStart());
	}

	/** Verificações que ainda cabem no mês: o menor entre o teto de contagem e o teto de gasto. */
	public int remaining() {
		int byCount = Math.max(0, config.monthlyVerificationLimit() - startedThisCycle());
		return Math.min(byCount, remainingByBudget());
	}

	public BigDecimal estimatedSpentUsd() {
		return config.pricePerVerificationUsd().multiply(BigDecimal.valueOf(startedThisCycle()))
			.setScale(2, RoundingMode.HALF_UP);
	}

	public BigDecimal budgetUsd() {
		return config.monthlyBudgetUsd();
	}

	/** Quantas verificações caberiam no mês só pelo teto de contagem configurado. */
	public int countLimit() {
		return config.monthlyVerificationLimit();
	}

	/** Teto efetivo em número de verificações: o menor entre contagem configurada e orçamento. */
	public int budgetLimitCount() {
		if (config.pricePerVerificationUsd().signum() <= 0) {
			return countLimit();
		}
		int byBudget = config.monthlyBudgetUsd()
			.divide(config.pricePerVerificationUsd(), 0, RoundingMode.DOWN).intValue();
		return Math.min(countLimit(), byBudget);
	}

	private int remainingByBudget() {
		if (config.pricePerVerificationUsd().signum() <= 0) {
			return Integer.MAX_VALUE;
		}
		BigDecimal left = config.monthlyBudgetUsd().subtract(estimatedSpentUsd());
		if (left.signum() <= 0) {
			return 0;
		}
		return left.divide(config.pricePerVerificationUsd(), 0, RoundingMode.DOWN).intValue();
	}

	/** Chamado antes de criar sessão: sem capacidade, a verificação não começa (e não cobra). */
	public void requireCapacity() {
		if (remaining() <= 0) {
			throw new ApiException(503, "IDENTITY_CAPACITY_EXHAUSTED",
				"A verificacao de identidade esta pausada ate o proximo ciclo. Nada foi cobrado.");
		}
	}
}