package com.socialcoisa.rede.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;

class ExternalJobRefreshSchedulerTest {

	private final ExternalJobImportService importService = mock(ExternalJobImportService.class);

	private final ExternalJobRepository repository = mock(ExternalJobRepository.class);

	@Test
	void primeiraCargaRodaNoBootSemSegurarAInicializacao() {
		when(importService.refresh()).thenReturn(1);
		ExternalJobRefreshScheduler agendador = new ExternalJobRefreshScheduler(importService, repository, true);

		agendador.seedIfEmpty();

		// Regressao: a carga nao pode depender da thread do agendador comum.
		verify(importService, timeout(5_000)).refresh();
	}

	@Test
	void erroQueNaoEExcecaoNaPrimeiraCargaNaoEscapaDaThread() throws Exception {
		when(importService.refresh()).thenThrow(new NoClassDefFoundError("com/exemplo/Sumiu"));
		ExternalJobRefreshScheduler agendador = new ExternalJobRefreshScheduler(importService, repository, true);

		agendador.seedIfEmpty();

		// Regressao: um Error matava a thread sem log e a carga simplesmente nao acontecia.
		verify(importService, timeout(5_000)).refresh();
		Thread.sleep(200);
		assertThat(Thread.getAllStackTraces().keySet().stream().map(Thread::getName))
			.doesNotContain("vagas-externas-boot");
	}

	@Test
	void desligadoNaoImportaNoBoot() {
		ExternalJobRefreshScheduler agendador = new ExternalJobRefreshScheduler(importService, repository, false);

		agendador.seedIfEmpty();

		verify(importService, after(300).never()).refresh();
	}

}