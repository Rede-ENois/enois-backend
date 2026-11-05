package com.socialcoisa.rede.coin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository;
import com.socialcoisa.rede.coin.domain.CoinEntry;
import com.socialcoisa.rede.coin.domain.CoinRate;

/**
 * O botão "Recalcular agora" rodava a mesma conta do cron e publicava uma cotação nova a cada
 * clique — no histórico da QA isso virou uma publicação por segundo, mexendo no mercado. Agora a
 * publicação é, no máximo, uma por período (24h): spam no botão não move o preço.
 */
class CoinEconomyServiceTests {

	private static final Instant START = Instant.parse("2026-10-01T03:10:00Z");

	private final AtomicReference<CoinRate> latest =
		new AtomicReference<>(new CoinRate("rate_1", 1, 10_000, null, START.minus(Duration.ofDays(2))));

	private final AtomicReference<Instant> now = new AtomicReference<>(START);

	/** Cada serviço ganha o SEU mock de cotação: re-trocar stub num mock compartilhado executa
	 * o answer antigo com o argumento do matcher (0) — daí o "cotação precisa ser positiva". */
	private CoinRateService rates;

	private final Clock clock = new Clock() {

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	};

	@Test
	void spamNoBotaoPublicaUmaVezSo() {
		CoinEconomyService service = service();

		var first = service.recalculate();
		assertThat(first.published()).isTrue();
		assertThat(first.currentMicros()).isEqualTo(9_900);
		assertThat(first.due()).isFalse();

		// O caso do histórico da QA: uma publicação por segundo, cada clique mexendo no mercado.
		for (int click = 0; click < 50; click++) {
			now.set(now.get().plus(Duration.ofSeconds(1)));
			var again = service.recalculate();
			assertThat(again.published()).isFalse();
			assertThat(again.currentMicros()).isEqualTo(9_900);
			assertThat(again.due()).isFalse();
		}
		verify(rates, times(1)).publish(anyLong(), anyString());
	}

	@Test
	void depoisDeUmPeriodoInteiroOPassoVoltaACheio() {
		CoinEconomyService service = service();
		service.recalculate();

		now.set(now.get().plus(Duration.ofHours(24)));
		assertThat(service.snapshot().due()).isTrue();
		var next = service.recalculate();

		assertThat(next.elapsedFraction()).isEqualTo(1.0);
		assertThat(next.published()).isTrue();
		assertThat(next.currentMicros()).isEqualTo(9_801);
		assertThat(next.due()).isFalse();
	}

	@Test
	void cronDepoisDePublicarNaoPublicaDeNovoNoMesmoDia() {
		CoinEconomyService service = service();
		service.recalculate();

		now.set(now.get().plus(Duration.ofHours(24)));
		service.scheduledRecalculate();
		service.scheduledRecalculate();
		service.scheduledRecalculate();

		// A do primeiro dia + a do dia seguinte; o resto do dia não publica nada.
		verify(rates, times(2)).publish(anyLong(), anyString());
	}

	@Test
	void painelMostraOPassoQueVaiSerPublicado() {
		CoinEconomyService service = service();
		service.recalculate();

		// Meio período depois o painel já antecipa o passo cheio do fechamento do dia.
		now.set(now.get().plus(Duration.ofHours(12)));
		var snapshot = service.snapshot();

		assertThat(snapshot.published()).isFalse();
		assertThat(snapshot.currentMicros()).isEqualTo(9_900);
		assertThat(snapshot.elapsedFraction()).isEqualTo(0.5);
		assertThat(snapshot.result().suggestedMicros()).isEqualTo(9_801);
		assertThat(snapshot.due()).isFalse();
		assertThat(snapshot.nextEvaluationAt()).isEqualTo(now.get().plus(Duration.ofHours(12)));
	}

	@Test
	void cronAntesDoPeriodoFecharNaoPublica() {
		CoinEconomyService service = service();
		service.recalculate();

		now.set(now.get().plus(Duration.ofHours(20)));
		service.scheduledRecalculate();

		verify(rates, times(1)).publish(anyLong(), anyString());
	}

	// ===== A rodada mensal COMPLETA: recalcula do zero e ignora a trava do período suave.

	@Test
	void aRodadaMensalPublicaMesmoComOPeriodoDe3hEmAberto() {
		// Giro 1,6× a meta com escassez 1,25 → índice 2 → cálculo completo: 10.000 × 2 = 20.000.
		CoinEconomyService service = service(80_000_000L);
		service.recalculate();

		// 1 hora depois do passo suave o período de 3h ainda está em aberto, mas a validação
		// mensal é de referência: recalcula e publica do mesmo jeito.
		now.set(now.get().plus(Duration.ofHours(1)));
		var monthly = service.monthlyRecalculate();

		assertThat(monthly.published()).isTrue();
		// 9.900 → 20.000 não é o passo de 1% do controle suave: é o preço completo recalculado.
		assertThat(monthly.result().suggestedMicros()).isEqualTo(20_000);
		verify(rates, times(2)).publish(anyLong(), anyString());
	}

	@Test
	void aRodadaMensalNaoParteDoUltimoValor() {
		// A cotação vigente está em 90.000 (histórico qualquer); o cálculo completo com as
		// métricas atuais tem que devolver o MESMO preço que devolveria se a vigente fosse 1.000.
		latest.set(new CoinRate("rate_2", 1, 90_000, null, START.minus(Duration.ofDays(1))));
		CoinEconomyService service = service(80_000_000L);
		var deUmaVigenciaAlta = service.monthlyRecalculate();

		latest.set(new CoinRate("rate_3", 1, 1_000, null, START.minus(Duration.ofDays(1))));
		CoinEconomyService outro = service(80_000_000L);
		var deUmaVigenciaBaixa = outro.monthlyRecalculate();

		assertThat(deUmaVigenciaAlta.result().suggestedMicros()).isEqualTo(20_000);
		assertThat(deUmaVigenciaAlta.result().suggestedMicros())
			.isEqualTo(deUmaVigenciaBaixa.result().suggestedMicros());
	}

	@Test
	void aRodadaMensalSemMudancaNadaPublica() {
		// Giro 0,8× a meta com escassez 1,25 → índice 1 → o completo reproduz a âncora 10.000,
		// que é exatamente a cotação vigente: repetir o mês não publica linha nova.
		latest.set(new CoinRate("rate_1", 1, 10_000, null, START.minus(Duration.ofDays(1))));
		CoinEconomyService service = service(40_000_000L);

		var monthly = service.monthlyRecalculate();

		assertThat(monthly.published()).isFalse();
		verify(rates, times(0)).publish(anyLong(), anyString());
	}

	private CoinEconomyService service() {
		return service(0L);
	}

	private CoinEconomyService service(long escrowUsage) {
		rates = mock(CoinRateService.class);
		CoinLedgerRepository ledger = mock(CoinLedgerRepository.class);
		// O stub específico do escrow vem DEPOIS do coringa: no Mockito o último stub que casa
		// vence — assim só o ESCROW_HOLD se move e emissão/P2P/taxas ficam zeradas (um stub
		// coringa sozinho devolveria o mesmo valor para TODOS os tipos e estragaria a conta).
		when(ledger.sumAmountByKindBetween(any(), any(), any())).thenReturn(0L);
		when(ledger.sumAmountByKindBetween(eq(CoinEntry.Kind.ESCROW_HOLD), any(), any()))
			.thenReturn(escrowUsage);

		when(rates.latest()).thenAnswer(invocation -> latest.get());
		when(rates.publish(anyLong(), anyString())).thenAnswer(invocation -> {
			CoinRate rate = CoinRate.create(invocation.getArgument(0), invocation.getArgument(1), now.get());
			latest.set(rate);
			return rate;
		});

		CoinService coins = mock(CoinService.class);
		when(coins.supply())
			.thenReturn(new CoinService.SupplyView(2_000_000_000, 1_000_000_000, 1_000_000_000,
				1_000_000_000, true));

		return new CoinEconomyService(ledger, rates, coins, clock, true, 24, 10_000, 30, 0.05, 0.5, 1.0,
			0.5, 0.25, 0.01, 1_000, 1_000_000);
	}

}