package com.socialcoisa.rede.emotes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class EmoteCatalogSchedulerTest {

	private final EmoteCatalogService servico = mock(EmoteCatalogService.class);

	/**
	 * Espera a chamada da thread de boot por um latch, e nao por verify(timeout): `refresh()` e
	 * `synchronized`, e o verify com timeout segura o monitor do mock enquanto espera. Se a thread
	 * de boot chegasse depois do verify (suite inteira, maquina ocupada), ela ficava bloqueada os
	 * 15s inteiros e o teste falhava sem regressao nenhuma.
	 */
	private final CountDownLatch chamado = new CountDownLatch(1);

	@Test
	void montaNoBootEmOutraThreadSemSegurarAInicializacao() throws Exception {
		EmoteCatalogScheduler agendador = new EmoteCatalogScheduler(servico, true);
		doAnswer(invocacao -> {
			chamado.countDown();
			return null;
		}).when(servico).refresh();

		long inicio = System.nanoTime();
		agendador.warmUpOnBoot();
		long decorrido = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

		// Regressao: o boot nao pode esperar as listagens do FFZ (segundos cada), nem depender da thread
		// do agendador comum. A janela do verify e folgada de proposito: a suite inteira roda em
		// paralelo com outras classes e 5s estouravam por contencao, nao por regressao.
		assertThat(decorrido).isLessThan(1_000);
		assertThat(chamado.await(15, TimeUnit.SECONDS)).isTrue();
		verify(servico).refresh();
	}

	@Test
	void erroQueNaoEExcecaoNoBootNaoEscapaDaThread() throws Exception {
		EmoteCatalogScheduler agendador = new EmoteCatalogScheduler(servico, true);
		doAnswer(invocacao -> {
			chamado.countDown();
			throw new NoClassDefFoundError("com/exemplo/Sumiu");
		}).when(servico).refresh();

		agendador.warmUpOnBoot();

		// Regressao: um Error matava a thread sem log e a montagem simplesmente nao acontecia.
		assertThat(chamado.await(15, TimeUnit.SECONDS)).isTrue();
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