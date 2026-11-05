package com.socialcoisa.rede.coin.application;

import com.socialcoisa.rede.coin.domain.CoinRate;

/**
 * A matemática da cotação automática — pura, sem Spring, testável isoladamente.
 *
 * <p>A cotação é um CONTROLADOR com três sinais:</p>
 * <ul>
 *   <li><b>Giro</b>: uso real da moeda (escrow de marketplace/serviços, P2P com peso menor) e
 *   <b>retorno ao cofre</b> (compras da loja e taxas) contra uma meta de giro.</li>
 *   <li><b>Escassez</b>: quanto menos faíscas restam no cofre em relação ao total, mais cada
 *   faísca vale (multiplicador de 1× a 1×+peso).</li>
 *   <li><b>Emissão</b>: diluição — mais faíscas emitidas para o mesmo uso, menos valor por
 *   faísca.</li>
 * </ul>
 *
 * <p>Se o giro para (ninguém usa e nada volta ao cofre), o índice cai e o preço desce: a
 * tesouraria precisa de retorno para não secar. Tudo é limitado por um passo máximo por execução
 * e por piso/teto de preço.</p>
 */
public final class CoinEconomyCalculator {

	private CoinEconomyCalculator() {
	}

	public record Params(int windowDays, double targetTurnover, double p2pWeight, double maxUsageRatio,
			double scarcityWeight, double sensitivity, double maxStep, long minMicros, long maxMicros) {

		public static Params defaults() {
			// Piso de 0,1 centavo por faísca: permite o preço cair quando a economia trava.
			return new Params(30, 0.05, 0.5, 1.0, 0.5, 0.25, 0.01,
				CoinRate.MICROS_PER_CENT / 10, CoinRate.MICROS_PER_CENT * 100);
		}

		public Params {
			// Parâmetros vêm de env; normaliza para o cálculo nunca ficar sem limites.
			windowDays = Math.max(1, Math.min(windowDays, 365));
			targetTurnover = clamp(targetTurnover, 0.0001, 10);
			p2pWeight = clamp(p2pWeight, 0, 1);
			maxUsageRatio = clamp(maxUsageRatio, 0.01, 100);
			scarcityWeight = clamp(scarcityWeight, 0, 2);
			sensitivity = clamp(sensitivity, 0.01, 5);
			maxStep = clamp(maxStep, 0.001, 0.5);
			minMicros = Math.max(1, minMicros);
			maxMicros = Math.max(minMicros, maxMicros);
		}
	}

	/**
	 * O que o ledger mediu na janela (milésimos de faísca).
	 *
	 * @param treasuryInflow compras da loja + taxas de marketplace que VOLTARAM ao cofre
	 * @param treasuryBalance o que ainda está no cofre hoje (escassez)
	 */
	public record Metrics(long escrowUsage, long p2pUsage, long treasuryInflow, long issuance,
			long circulation, long treasuryBalance) {
	}

	public record Result(long usage, long effectiveUsage, double scarcity, double index,
			long suggestedMicros) {
	}

	/**
	 * O CÁLCULO COMPLETO mensal: o preço sai direto das métricas da janela multiplicando a
	 * âncora pelo índice — NÃO parte do último valor publicado e NÃO passa pelo limite de passo
	 * (o passo existe para suavizar o controle diário; a rodada mensal é a validação de
	 * referência e corrige o preço inteiro de uma vez, com piso/teto como única travas).
	 */
	public static Result absoluteEvaluate(long anchorMicros, Metrics metrics, Params params) {
		Core core = core(metrics, params);
		long anchor = Math.max(1, anchorMicros);
		long next = core.circulation() == 0 ? anchor : Math.round(anchor * core.index());
		return new Result(core.usage(), core.effective(),
			Math.round(core.scarcity() * 100) / 100.0, core.index(), clamp(next, params));
	}

	/**
	 * Um passo da cotação — sempre o passo de UM PERÍODO (3h por padrão). Quem decide se já é
	 * hora de publicar é o {@code CoinEconomyService}: a conta não olha o relógio nem quantas
	 * vezes o botão foi clicado, só o movimento medido no ledger.
	 */
	public static Result evaluate(long previousMicros, Metrics metrics, Params params) {
		long previous = Math.max(1, previousMicros);
		Core core = core(metrics, params);
		// Sem estoque em circulação não há o que reavaliar: o preço fica onde está.
		if (core.circulation() == 0) {
			return new Result(core.usage(), 0, 1.0, 1.0, clamp(previous, params));
		}
		double step = params.maxStep();
		double delta = clamp((core.index() - 1.0) * params.sensitivity(), -step, step);
		long next = Math.round(previous * (1.0 + delta));
		return new Result(core.usage(), core.effective(),
			Math.round(core.scarcity() * 100) / 100.0, core.index(), clamp(next, params));
	}

	/** O miolo da conta, compartilhado pelo passo e pelo cálculo completo. */
	private record Core(long usage, long circulation, long effective, double scarcity, double index) {
	}

	private static Core core(Metrics metrics, Params params) {
		long circulation = Math.max(0, metrics.circulation());
		long treasury = Math.max(0, metrics.treasuryBalance());
		long escrow = Math.max(0, metrics.escrowUsage());
		long p2p = Math.max(0, metrics.p2pUsage());
		long inflow = Math.max(0, metrics.treasuryInflow());
		long issuance = Math.max(0, metrics.issuance());
		long usage = saturatingAdd(saturatingAdd(escrow, p2p), inflow);
		if (circulation == 0) {
			return new Core(usage, 0, 0, 1.0, 1.0);
		}

		// Menos faíscas no cofre (em relação ao total) = mais escassez = mais valor por faísca.
		long total = saturatingAdd(treasury, circulation);
		double treasuryRatio = total <= 0 ? 0 : (double) treasury / total;
		double scarcity = 1.0 + params.scarcityWeight() * (1.0 - treasuryRatio);

		// P2P é circulação, não consumo — entra com peso menor. O giro total é limitado a uma volta
		// do estoque por janela: ping-pong entre contas não pode inflar a cotação sozinho.
		double weighted = escrow + p2p * params.p2pWeight() + inflow;
		double effective = Math.min(weighted, circulation * params.maxUsageRatio());
		double target = circulation * params.targetTurnover();
		double ratio = target <= 0 ? scarcity : (effective / target) * scarcity;
		double dilution = 1.0 + (double) issuance / circulation;
		return new Core(usage, circulation, Math.round(effective), scarcity, ratio / dilution);	}

	private static long clamp(long value, Params params) {
		return Math.max(params.minMicros(), Math.min(value, params.maxMicros()));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(value, max));
	}

	private static long saturatingAdd(long a, long b) {
		long sum = a + b;
		return sum < 0 ? Long.MAX_VALUE : sum;
	}
}