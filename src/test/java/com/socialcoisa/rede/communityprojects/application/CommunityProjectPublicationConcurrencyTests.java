package com.socialcoisa.rede.communityprojects.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.communityprojects.application.port.out.CommunityProjectRepository;
import com.socialcoisa.rede.communityprojects.domain.CommunityProject;
import com.socialcoisa.rede.communityprojects.domain.CommunityProjectStatus;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

/** Transações reais: a primeira leitura fica parada até a concorrente concluir ou bloquear no H2. */
@SpringBootTest
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:community_project_publication_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
	"rede.outbox.worker-initial-delay-ms=3600000",
	"rede.outbox.worker-delay-ms=3600000" })
class CommunityProjectPublicationConcurrencyTests {

	@Autowired private CommunityProjectService projetos;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TransactionTemplate transacoes;
	@MockitoSpyBean private CommunityProjectRepository repositorio;
	/** O contexto sem autenticação não deve redirecionar o helper estático de outros testes. */
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	private final CountDownLatch primeiraLeitura = new CountDownLatch(1);
	private final CountDownLatch liberarPrimeira = new CountDownLatch(1);
	private final CountDownLatch segundaLeitura = new CountDownLatch(1);
	private final AtomicReference<Thread> primeiraThread = new AtomicReference<>();
	private final AtomicBoolean leituraPausada = new AtomicBoolean();
	private String projetoId;
	private String autorId;

	@TestConfiguration
	static class RelogioConfig {
		@Bean
		@Primary
		Clock relogioPublicacao() {
			return Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC);
		}
	}

	@AfterEach
	void limpar() {
		liberarPrimeira.countDown();
		jdbc.update("delete from outbox_events where aggregate_id = ?", projetoId);
		jdbc.update("delete from community_projects where id = ?", projetoId);
	}

	@Test
	void edicaoIniciadaAntesDaAprovacaoPreservaPublicacaoELancamentoUnico() throws Exception {
		prepararProjeto();
		Corrida corrida = concorrer(this::editar, this::aprovar);
		assertThat(corrida.primeira().erro()).isNull();
		assertThat(corrida.segunda().erro()).isNull();
		CommunityProject persistido = repositorio.findById(projetoId).orElseThrow();
		CommunityProjectStatus estadoAposCorrida = persistido.status();
		var publicacaoAposCorrida = persistido.publishedAt();
		// No código antigo, a edição restaura SUBMITTED e permite emitir o segundo lançamento real.
		if (estadoAposCorrida == CommunityProjectStatus.SUBMITTED) aprovar();
		assertSoftly(prova -> {
			prova.assertThat(estadoAposCorrida).isEqualTo(CommunityProjectStatus.PUBLISHED);
			prova.assertThat(publicacaoAposCorrida).isEqualTo(corrida.segunda().projeto().publishedAt());
			prova.assertThat(persistido.title()).isEqualTo("Projeto editado");
			prova.assertThat(eventos()).isEqualTo(1);
		});
		assertThatThrownBy(this::aprovar).isInstanceOfSatisfying(ApiException.class,
			erro -> assertThat(erro.code()).isEqualTo("NOT_SUBMITTED"));
		assertThat(eventos()).isEqualTo(1);
	}

	@Test
	void buscaPublicaNaoEsperaLockDaAprovacao() throws Exception {
		prepararProjeto();
		Corrida corrida = concorrer(this::aprovar, () -> {
			assertThat(projetos.search(null, null, 10, 0).items()).extracting(CommunityProject::id).doesNotContain(projetoId);
			return null;
		});
		assertThat(corrida.bloqueada()).as("a busca pública não adquire lock de escrita").isFalse();
		assertThat(corrida.primeira().erro()).isNull();
		assertThat(corrida.segunda().erro()).isNull();
		assertThat(projetos.search(null, null, 10, 0).items()).extracting(CommunityProject::id).contains(projetoId);
	}

	@ParameterizedTest
	@ValueSource(strings = { "editar", "recusar", "suspender", "reenviar", "restaurar", "remover" })
	void mutadoresEsperamAprovacaoAntesDeLerEstado(String operacao) throws Exception {
		prepararProjeto();
		Corrida corrida = concorrer(this::aprovar, () -> alterar(operacao));
		assertThat(corrida.primeira().erro()).isNull();
		assertThat(corrida.bloqueada()).as("a segunda transação precisa esperar o lock de projeto").isTrue();
		assertThat(corrida.leuAntesDaLiberacao()).as("o estado só pode ser lido depois do lock").isFalse();
		verificarMutacao(operacao, corrida);
		assertThat(eventos()).isEqualTo(1);
	}

	private void verificarMutacao(String operacao, Corrida corrida) {
		String codigo = switch (operacao) {
			case "recusar" -> "NOT_SUBMITTED";
			case "reenviar" -> "NOT_REJECTED";
			case "restaurar" -> "NOT_SUSPENDED";
			default -> null;
		};
		if (codigo == null) assertThat(corrida.segunda().erro()).isNull();
		else assertThat(corrida.segunda().erro()).isNotNull().extracting(ApiException::code).isEqualTo(codigo);
		if (operacao.equals("remover")) {
			assertThat(repositorio.findById(projetoId)).isEmpty();
			return;
		}
		CommunityProject persistido = repositorio.findById(projetoId).orElseThrow();
		assertThat(persistido.publishedAt()).isEqualTo(corrida.primeira().projeto().publishedAt());
		assertThat(persistido.status()).isEqualTo(operacao.equals("suspender")
			? CommunityProjectStatus.SUSPENDED : CommunityProjectStatus.PUBLISHED);
		assertThat(persistido.title()).isEqualTo(operacao.equals("editar") ? "Projeto editado" : "Projeto concorrente");
	}

	private CommunityProject alterar(String operacao) {
		return switch (operacao) {
			case "editar" -> editar();
			case "recusar" -> projetos.reject(projetoId, "revisor_concorrente", "Ajustar apresentação");
			case "suspender" -> projetos.suspend(projetoId, "revisor_concorrente", "Revisar apresentação");
			case "reenviar" -> projetos.resubmit(projetoId, autorId);
			case "restaurar" -> projetos.unsuspend(projetoId, "revisor_concorrente");
			case "remover" -> {
				projetos.remove(projetoId, autorId);
				yield null;
			}
			default -> throw new IllegalArgumentException("Operação de teste desconhecida: " + operacao);
		};
	}

	private void prepararProjeto() {
		autorId = "autor_corrida_" + UUID.randomUUID().toString().replace("-", "");
		projetoId = projetos.submit(autorId, null, "Projeto concorrente", null, null,
			null, null, null, null, null).id();
		doAnswer(invocacao -> observarLeitura(invocacao.callRealMethod())).when(repositorio).findById(anyString());
		doAnswer(invocacao -> observarLeitura(invocacao.callRealMethod())).when(repositorio).findByIdForUpdate(anyString());
	}

	private Object observarLeitura(Object resultado) throws InterruptedException {
		if (Thread.currentThread() != primeiraThread.get()) {
			segundaLeitura.countDown();
			return resultado;
		}
		if (leituraPausada.compareAndSet(false, true)) {
			primeiraLeitura.countDown();
			assertThat(liberarPrimeira.await(15, TimeUnit.SECONDS)).as("liberação da primeira leitura").isTrue();
		}
		return resultado;
	}

	private Corrida concorrer(Supplier<CommunityProject> primeira, Supplier<CommunityProject> segunda) throws Exception {
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<Resultado> primeiraExecucao = executor.submit(() -> {
				primeiraThread.set(Thread.currentThread());
				return executar(primeira, new AtomicInteger());
			});
			try {
				assertThat(primeiraLeitura.await(10, TimeUnit.SECONDS)).as("primeira leitura real").isTrue();
				AtomicInteger segundaSessao = new AtomicInteger();
				Future<Resultado> segundaExecucao = executor.submit(() -> executar(segunda, segundaSessao));
				boolean bloqueada = esperarConclusaoOuBloqueio(segundaExecucao, segundaSessao);
				boolean leuAntes = segundaLeitura.getCount() == 0;
				liberarPrimeira.countDown();
				return new Corrida(primeiraExecucao.get(10, TimeUnit.SECONDS),
					segundaExecucao.get(10, TimeUnit.SECONDS), bloqueada, leuAntes);
			} finally {
				liberarPrimeira.countDown();
			}
		}
	}

	private Resultado executar(Supplier<CommunityProject> operacao, AtomicInteger sessao) {
		try {
			CommunityProject projeto = transacoes.execute(estado -> {
				sessao.set(jdbc.queryForObject("select session_id()", Integer.class));
				return operacao.get();
			});
			return new Resultado(projeto, null);
		} catch (ApiException erro) {
			return new Resultado(null, erro);
		}
	}

	private boolean esperarConclusaoOuBloqueio(Future<Resultado> execucao, AtomicInteger sessao) {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < limite) {
			if (execucao.isDone()) return false;
			Integer bloqueios = jdbc.queryForObject("""
				select count(*) from information_schema.sessions
				where session_id = ? and session_state = 'BLOCKED'
				""", Integer.class, sessao.get());
			if (bloqueios != null && bloqueios > 0) return true;
		}
		throw new AssertionError("A segunda transação não concluiu nem bloqueou no prazo.");
	}

	private CommunityProject editar() {
		return projetos.edit(projetoId, autorId, "Projeto editado", null, null, null, null, null, null, null, null);
	}

	private CommunityProject aprovar() {
		return projetos.approve(projetoId, "revisor_concorrente", null);
	}

	private int eventos() {
		return jdbc.queryForObject("""
			select count(*) from outbox_events where aggregate_id = ? and event_type = 'CommunityProjectPublished'
			""", Integer.class, projetoId);
	}

	private record Resultado(CommunityProject projeto, ApiException erro) { }
	private record Corrida(Resultado primeira, Resultado segunda, boolean bloqueada, boolean leuAntesDaLiberacao) { }
}
