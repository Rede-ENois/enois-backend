package com.socialcoisa.rede.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobStatus;
import org.junit.jupiter.api.Tag;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

@Tag("critico")
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"spring.datasource.url=jdbc:h2:mem:job_edit_b1;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
	"rede.outbox.worker-initial-delay-ms=3600000",
	"rede.outbox.worker-delay-ms=3600000" })
class JobEditConcurrencyTests {

	@Autowired private JobService servico;
	@Autowired private TransactionTemplate transacoes;
	@MockitoSpyBean private JobRepository repositorio;
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	@Test
	void edicaoPreservaEncerramentoEPrazoConcorrentes() throws Exception {
		String vagaId = "job_b1_" + UUID.randomUUID().toString().replace("-", "");
		String autorId = "autor_b1";
		Instant publicadaEm = Instant.now().minusSeconds(86400).truncatedTo(ChronoUnit.SECONDS);
		Instant prazoVencido = publicadaEm.plusSeconds(3600);
		repositorio.save(Job.create(vagaId, "Pessoa Desenvolvedora", "Empresa B1", null, "Remoto",
			"CLT", "PLENO", null, "BRL", new BigDecimal("1500"), null, "MONTH",
			"Vaga de teste com valor.", List.of(), List.of(), autorId, publicadaEm, publicadaEm, prazoVencido));

		CountDownLatch alterada = new CountDownLatch(1);
		CountDownLatch liberar = new CountDownLatch(1);
		CountDownLatch edicaoIniciada = new CountDownLatch(1);
		AtomicReference<Thread> threadDaEdicao = new AtomicReference<>();

		doAnswer(invocacao -> {
			Object resultado = invocacao.callRealMethod();
			if (Thread.currentThread() == threadDaEdicao.get()) {
				edicaoIniciada.countDown();
			}
			return resultado;
		}).when(repositorio).findById(vagaId);
		doAnswer(invocacao -> {
			if (Thread.currentThread() == threadDaEdicao.get()) {
				edicaoIniciada.countDown();
			}
			return invocacao.callRealMethod();
		}).when(repositorio).findByIdForUpdate(vagaId);

		var pool = Executors.newFixedThreadPool(2);
		try {
			var ciclo = pool.submit(() -> {
				try {
					return transacoes.execute(status -> {
						Job encerrada = servico.close(vagaId, autorId);
						servico.setDeadline(vagaId, autorId, null);
						alterada.countDown();
						esperar(liberar);
						return encerrada;
					});
				}
				finally {
					alterada.countDown();
				}
			});
			assertThat(alterada.await(10, TimeUnit.SECONDS)).isTrue();

			var edicao = pool.submit(() -> {
				threadDaEdicao.set(Thread.currentThread());
				return servico.update(vagaId, "Pessoa Desenvolvedora II", "Empresa B1", null,
					"Remoto", "CLT", "PLENO", null, "BRL", "1500", null, "MONTH",
					"Vaga de teste com valor.", List.of(), List.of(), autorId);
			});
			assertThat(edicaoIniciada.await(10, TimeUnit.SECONDS)).isTrue();
			liberar.countDown();

			Job encerrada = ciclo.get(20, TimeUnit.SECONDS);
			edicao.get(20, TimeUnit.SECONDS);

			Job persistida = repositorio.findById(vagaId).orElseThrow();
			assertThat(persistida.status()).isEqualTo(JobStatus.CLOSED);
			assertThat(persistida.closedAt()).isCloseTo(encerrada.closedAt(), within(1, ChronoUnit.MICROS));
			assertThat(persistida.applicationDeadline()).isNull();
			assertThat(persistida.title()).isEqualTo("Pessoa Desenvolvedora II");
		}
		finally {
			liberar.countDown();
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
	}

	private static void esperar(CountDownLatch sinal) {
		try {
			assertThat(sinal.await(10, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException interrompida) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Espera de concorrência interrompida.", interrompida);
		}
	}
}
