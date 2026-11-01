package com.socialcoisa.rede.coin;

import java.util.function.Supplier;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Apoio dos testes da moeda.
 *
 * A tesouraria é UMA conta compartilhada por todo o processo (a base H2 é a mesma para todas as
 * classes) e toda abertura de carteira nova a debita (airdrop e presença). Se qualquer outra
 * escrita na tesouraria cair entre a leitura e a escrita dessa abertura, o lock otimista recusa
 * com 409 COIN_CONCURRENCY, que é, por contrato, "tente de novo". O teste que só PREPARA carteiras
 * se comporta como o cliente: tenta de novo, com limite, e falha alto se a disputa não passar.
 */
public final class CoinTestSupport {

	static final int MAX_ATTEMPTS = 20;
	private static final long BACKOFF_MS = 25;

	private CoinTestSupport() {
	}

	/** Repete só o 409 COIN_CONCURRENCY; qualquer outro erro sobe na hora e esgotar as tentativas também. */
	public static <T> T retryOnCoinConcurrency(Supplier<T> action) {
		ApiException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return action.get();
			}
			catch (ApiException rejected) {
				if (!"COIN_CONCURRENCY".equals(rejected.code())) {
					throw rejected;
				}
				last = rejected;
				pause(BACKOFF_MS * attempt);
			}
		}
		throw new AssertionError("COIN_CONCURRENCY persistiu por " + MAX_ATTEMPTS + " tentativas", last);
	}

	private static void pause(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("interrompido enquanto esperava a tesouraria", interrupted);
		}
	}
}
