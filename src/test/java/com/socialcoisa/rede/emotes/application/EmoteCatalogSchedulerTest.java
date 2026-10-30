package com.socialcoisa.rede.emotes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class EmoteCatalogSchedulerTest {

	private final EmoteCatalogService servico = mock(EmoteCatalogService.class);

	@Test
	void montaNoBootEmOutraThreadSemSegurarAInicializacao() {
		EmoteCatalogScheduler agendador = new EmoteCatalogScheduler(servico, true);

		long inicio = System.nanoTime();
		agendador.warmUpOnBoot();
		long decorrido = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

		// Regressao: o boot nao pode esperar as listagens do FFZ (segundos cada), nem depender da thread
		// do agendador comum. A janela do verify e folgada de proposito: a suite inteira roda em
		// paralelo com outras classes e 5s estouravam por contencao, nao por regressao.
		assertThat(decorrido).isLessThan(1_000);
		verify(servico, timeout(15_000)).refresh();
	}

	@Test
	void erroQueNaoEExcecaoNoBootNaoEscapaDaThread() throws Exception {
		EmoteCatalogScheduler agendador = new EmoteCatalogScheduler(servico, true);
		doThrow(new NoClassDefFoundError("com/exemplo/Sumiu")).when(servico).refresh();

		agendador.warmUpOnBoot();

		// Regressao: um Error matava a thread sem log e a montagem simplesmente nao acontecia.
		verify(servico, timeout(15_000)).refresh();
		Thread.sleep(200);
		assertThat(Thread.getAllStackTraces().keySet().stream().map(Thread::getName))
			.doesNotContain("figurinhas-boot");
	}

	@Test
	void desligadoNaoMontaNoBoot() {
		EmoteCatalogScheduler agendador = new EmoteCatalogScheduler(servico, false);

		agendador.warmUpOnBoot();
		agendador.refreshDaily();

		verify(servico, after(300).never()).refresh();
	}

	@Test
	void rodadaAgendadaMontaUmaVezESegueDePeQuandoFalha() {
		EmoteCatalogScheduler agendador = new EmoteCatalogScheduler(servico, true);
		agendador.refreshDaily();
		verify(servico, times(1)).refresh();

		doThrow(new IllegalStateException("provedor fora do ar")).when(servico).refresh();
		agendador.refreshDaily();

		verify(servico, times(2)).refresh();
	}

}