package com.socialcoisa.rede.coin.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository;
import com.socialcoisa.rede.coin.domain.CoinEntry;

/**
 * Reavalia a cotação de REFERÊNCIA da faísca a partir do uso real.
 *
 * <p>Fontes (janela configurável, padrão 30 dias): escrow de marketplace/serviços
 * ({@code ESCROW_HOLD}), transferências P2P ({@code TRANSFER_IN}) e emissão automática
 * ({@code REWARD}). A fórmula vive no {@link CoinEconomyCalculator}; aqui só se buscam as
 * métricas no ledger e se publica o resultado no histórico da cotação.</p>
 *
 * <p>Salvaguardas: passo máximo por execução, piso/teto de preço, peso menor para P2P e teto de
 * uso por janela (ping-pong entre contas não infla o preço). Se não houver circulação, nada muda.
 * Uma vez por mês a rodada COMPLETA recalcula o preço do zero (âncora × índice, sem partir do
 * último valor e sem o limite de passo). O valor-base da emissão automática continua sendo o da
 * cotação inicial; a conversão fica no {@link CoinService#scaledReward(long)}.</p>
 */
@Service
public class CoinEconomyService {

	private static final Logger LOG = LoggerFactory.getLogger(CoinEconomyService.class);

	/**
	 * @param elapsedFraction quanto do período já passou desde a última publicação (1.0 = período
	 * completo); é só informativo — o passo é sempre o do período inteiro
	 * @param due o período já fechou, então a próxima avaliação pode publicar agora; fica falso
	 * assim que uma publicação acontece, até o período seguinte fechar
	 * @param nextEvaluationAt quando a próxima publicação pode acontecer
	 */
	public record Summary(int windowDays, CoinEconomyCalculator.Metrics metrics,
			CoinEconomyCalculator.Result result, long previousMicros, long currentMicros, boolean published,
			double elapsedFraction, boolean due, Instant nextEvaluationAt) {
	}

	private final CoinLedgerRepository ledger;
	private final CoinRateService rates;
	private final CoinService coins;
	private final Clock clock;
	private final boolean enabled;
	private final CoinEconomyCalculator.Params params;
	/** Período de referência de uma reavaliação suave (o cron é de 3h em 3h). */
	private final Duration period;
	/** Âncora do cálculo completo mensal: o preço de referência multiplicado pelo índice. */
	private final long anchorMicros;

	public CoinEconomyService(CoinLedgerRepository ledger, CoinRateService rates, CoinService coins, Clock clock,
			@Value("${rede.coin.economy.enabled:true}") boolean enabled,
			@Value("${rede.coin.economy.period-hours:3}") int periodHours,
			@Value("${rede.coin.economy.anchor-micros:10000}") long anchorMicros,
			@Value("${rede.coin.economy.window-days:30}") int windowDays,
			@Value("${rede.coin.economy.target-turnover:0.05}") double targetTurnover,
			@Value("${rede.coin.economy.p2p-weight:0.5}") double p2pWeight,
			@Value("${rede.coin.economy.max-usage-ratio:1.0}") double maxUsageRatio,
			@Value("${rede.coin.economy.scarcity-weight:0.5}") double scarcityWeight,
			@Value("${rede.coin.economy.sensitivity:0.25}") double sensitivity,
			@Value("${rede.coin.economy.max-step:0.01}") double maxStep,
			@Value("${rede.coin.economy.min-micros:1000}") long minMicros,
			@Value("${rede.coin.economy.max-micros:1000000}") long maxMicros) {
		this.ledger = ledger;
		this.rates = rates;
		this.coins = coins;
		this.clock = clock;
		this.enabled = enabled;
		this.period = Duration.ofHours(Math.max(1, periodHours));
		this.anchorMicros = Math.max(1, anchorMicros);
		this.params = new CoinEconomyCalculator.Params(windowDays, targetTurnover, p2pWeight, maxUsageRatio,
			scarcityWeight, sensitivity, maxStep, minMicros, maxMicros);
	}

	/** Execução periódica (a cada 3 horas por padrão): publica a nova cotação quando mudou. */
	@Scheduled(cron = "${rede.coin.economy.cron:0 10 */3 * * *}",
		zone = "${rede.coin.economy.zone:America/Sao_Paulo}")
	public void scheduledRecalculate() {
		if (!enabled) {
			return;
		}
		try {
			Summary summary = recalculate();
			if (summary.published()) {
				LOG.info("coin_economy quote_micros={} previous={} usage={} issuance={} circulation={}",
					summary.result().suggestedMicros(), summary.previousMicros(),
					summary.metrics().escrowUsage() + summary.metrics().p2pUsage(),
					summary.metrics().issuance(), summary.metrics().circulation());
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("coin_economy_failed error={}", failure.getMessage());
		}
	}

	/**
	 * A rodada mensal COMPLETA (1º de cada mês, no fuso configurado): recalcula o preço DO ZERO —
	 * âncora × índice da janela de 30 dias — sem partir do último valor publicado e sem o limite
	 * de passo. É a validação de referência da cotação: o controle de passo curto das 3h pode se
	 * acumular fora do rumo, e a rodada mensal corrige o preço inteiro de uma vez (piso/teto
	 * continuam valendo). Publica mesmo que o período de 3h não tenha fechado — a trava do
	 * período é do controle suave, não da validação mensal.
	 */
	@Scheduled(cron = "${rede.coin.economy.monthly-cron:0 40 3 1 * *}",
		zone = "${rede.coin.economy.zone:America/Sao_Paulo}")
	public void scheduledMonthlyRecalculate() {
		if (!enabled) {
			return;
		}
		try {
			Summary summary = monthlyRecalculate();
			if (summary.published()) {
				LOG.info("coin_economy_monthly quote_micros={} previous={} index={}",
					summary.result().suggestedMicros(), summary.previousMicros(),
					summary.result().index());
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("coin_economy_monthly_failed error={}", failure.getMessage());
		}
	}

	/** A mesma conta da rodada mensal, chamada na mão (testes e administração). */
	public synchronized Summary monthlyRecalculate() {
		var latest = rates.latest();
		long current = latest.brlMicros();
		CoinEconomyCalculator.Metrics metrics = metrics();
		CoinEconomyCalculator.Result result = CoinEconomyCalculator.absoluteEvaluate(anchorMicros, metrics,
			params);
		boolean published = false;
		if (enabled && result.suggestedMicros() != current) {
			rates.publish(result.suggestedMicros(), monthlyNote(metrics, result));
			published = true;
		}
		Instant now = clock.instant();
		return new Summary(params.windowDays(), metrics, result, current,
			published ? result.suggestedMicros() : current, published,
			elapsedFraction(latest.effectiveAt(), now), isOpen(nextEvaluationAt(latest.effectiveAt(), now),
				now), nextEvaluationAt(latest.effectiveAt(), now));
	}

	private String monthlyNote(CoinEconomyCalculator.Metrics metrics,
			CoinEconomyCalculator.Result result) {
		return "Cotação completa do mês (referência, calculada do zero): giro "
			+ faiscas(metrics.escrowUsage() + metrics.p2pUsage() + metrics.treasuryInflow())
			+ ", retorno ao cofre " + faiscas(metrics.treasuryInflow()) + ", emissão "
			+ faiscas(metrics.issuance()) + ", cofre " + faiscas(metrics.treasuryBalance())
			+ ", índice " + String.format(java.util.Locale.ROOT, "%.2f", result.index());
	}

	/**
	 * Publica a cotação do período, se o período já fechou. A cadência é a mesma para o cron e
	 * para o botão: **no máximo uma publicação por período** (24h). Clicar várias vezes no botão
	 * não move a cotação — só devolve o cálculo do momento e quando é a próxima avaliação.
	 *
	 * <p>Sem essa trava cada clique valia um dia de mercado: dava para mexer no preço quantas
	 * vezes quisesse, e o histórico da QA registrava uma publicação por segundo.</p>
	 *
	 * <p>Não é `@Transactional` de propósito: a transação da publicação tem que commitar ANTES de
	 * liberar a trava, senão quem entrasse logo depois ainda leria a cotação anterior (READ
	 * COMMITTED não vê o commit alheio) e publicaria de novo. Quem publica é
	 * {@link CoinRateService#publish}, com transação própria.</p>
	 */
	public synchronized Summary recalculate() {
		var latest = rates.latest();
		long previous = latest.brlMicros();
		Instant lastAt = latest.effectiveAt();
		Instant now = clock.instant();
		Instant next = nextEvaluationAt(lastAt, now);
		CoinEconomyCalculator.Metrics metrics = metrics();
		CoinEconomyCalculator.Result result = CoinEconomyCalculator.evaluate(previous, metrics, params);
		double elapsed = elapsedFraction(lastAt, now);
		boolean published = false;
		if (isOpen(next, now) && enabled && result.suggestedMicros() != previous) {
			rates.publish(result.suggestedMicros(), note(metrics, result));
			published = true;
			next = now.plus(period);
		}
		return new Summary(params.windowDays(), metrics, result, previous,
			published ? result.suggestedMicros() : previous, published, elapsed, isOpen(next, now), next);
	}

	/**
	 * Leitura para o painel: as mesmas métricas, sem publicar nada. A sugestão é o passo que a
	 * próxima publicação vai aplicar (o cálculo é o mesmo do cron, com o movimento da janela).
	 */
	@Transactional
	public Summary snapshot() {
		var latest = rates.latest();
		long previous = latest.brlMicros();
		Instant now = clock.instant();
		Instant lastAt = latest.effectiveAt();
		double elapsed = elapsedFraction(lastAt, now);
		CoinEconomyCalculator.Metrics metrics = metrics();
		Instant next = nextEvaluationAt(lastAt, now);
		return new Summary(params.windowDays(), metrics,
			CoinEconomyCalculator.evaluate(previous, metrics, params), previous, previous, false, elapsed,
			isOpen(next, now), next);
	}

	/** O período já fechou: uma avaliação neste instante pode publicar. */
	private static boolean isOpen(Instant nextEvaluationAt, Instant now) {
		return !now.isBefore(nextEvaluationAt);
	}

	/** Quando o período fecha: `lastAt + período`; sem publicação anterior, agora. */
	private Instant nextEvaluationAt(Instant lastPublishedAt, Instant now) {
		return lastPublishedAt == null ? now : lastPublishedAt.plus(period);
	}

	/** Fração do período já decorrida desde a última publicação, limitada a [0, 1]. */
	double elapsedFraction(Instant lastPublishedAt, Instant now) {
		if (lastPublishedAt == null) {
			return 1.0;
		}
		long seconds = Duration.between(lastPublishedAt, now).getSeconds();
		if (seconds <= 0) {
			return 0.0;
		}
		return Math.min(1.0, seconds / (double) period.toSeconds());
	}

	private CoinEconomyCalculator.Metrics metrics() {
		Instant to = clock.instant();
		Instant from = to.minus(Duration.ofDays(params.windowDays()));
		long escrow = ledger.sumAmountByKindBetween(CoinEntry.Kind.ESCROW_HOLD, from, to);
		long p2p = ledger.sumAmountByKindBetween(CoinEntry.Kind.TRANSFER_IN, from, to);
		long issuance = ledger.sumAmountByKindBetween(CoinEntry.Kind.REWARD, from, to);
		// Retorno ao cofre: compras da loja (PURCHASE sai do comprador e volta para a tesouraria) e
		// taxas de marketplace (FEE_IN). Sem esse retorno a tesouraria seca e a economia trava.
		long store = Math.max(0, -ledger.sumAmountByKindBetween(CoinEntry.Kind.PURCHASE, from, to));
		long fees = Math.max(0, ledger.sumAmountByKindBetween(CoinEntry.Kind.FEE_IN, from, to));
		long inflow = saturatingAdd(store, fees);
		var supply = coins.supply();
		return new CoinEconomyCalculator.Metrics(escrow, p2p, inflow, issuance, supply.circulacao(),
			supply.cofre());
	}

	private String note(CoinEconomyCalculator.Metrics metrics, CoinEconomyCalculator.Result result) {
		return "Reavaliação automática por uso: giro " + faiscas(metrics.escrowUsage() + metrics.p2pUsage()
			+ metrics.treasuryInflow()) + ", retorno ao cofre " + faiscas(metrics.treasuryInflow())
			+ ", emissão " + faiscas(metrics.issuance()) + ", cofre " + faiscas(metrics.treasuryBalance())
			+ ", índice " + String.format(java.util.Locale.ROOT, "%.2f", result.index());
	}

	private static long saturatingAdd(long a, long b) {
		long sum = a + b;
		return sum < 0 ? Long.MAX_VALUE : sum;
	}

	private static String faiscas(long mf) {
		return java.math.BigDecimal.valueOf(mf, 3).stripTrailingZeros().toPlainString();
	}
}