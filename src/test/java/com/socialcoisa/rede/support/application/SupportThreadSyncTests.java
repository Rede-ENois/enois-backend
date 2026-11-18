package com.socialcoisa.rede.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;
import com.socialcoisa.rede.support.application.port.out.GithubIssuesGateway;
import com.socialcoisa.rede.support.application.port.out.SupportRequestRepository;
import com.socialcoisa.rede.support.domain.SupportCategory;
import com.socialcoisa.rede.support.domain.SupportMessage;
import com.socialcoisa.rede.support.domain.SupportMessageDirection;
import com.socialcoisa.rede.support.domain.SupportRequest;

@SpringBootTest
@TestPropertySource(properties = {
	"spring.datasource.url=jdbc:h2:mem:support_thread_sync;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
	"spring.datasource.hikari.maximum-pool-size=4",
	"rede.outbox.worker-initial-delay-ms=3600000",
	"rede.outbox.worker-delay-ms=3600000" })
class SupportThreadSyncTests {

	private static final AtomicInteger ISSUES = new AtomicInteger(1_800_000_000);

	private final int issue = ISSUES.incrementAndGet();
	private final long commentId = 8_000_000_000L + issue;
	private final String requestId = "support_sync_" + issue;
	private final String adminId = "admin_sync_" + issue;

	@Autowired private SupportThreadService thread;
	@Autowired private SupportWebhookService webhook;
	@Autowired private SupportGithubSync sync;
	@Autowired private SupportRequestRepository requests;
	@Autowired private TransactionTemplate transacoes;
	@Autowired private JdbcTemplate jdbc;

	@MockitoBean private GithubIssuesGateway gateway;
	@MockitoBean private GitHubIdentityService identities;
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	@BeforeEach
	void preparar() {
		SupportRequest request = SupportRequest.create(requestId, "fixture", "Assunto", "Descrição",
			"contato", null, SupportCategory.OTHER, Instant.parse("2024-01-01T00:00:00Z"));
		requests.save(request.withGithubIssue(issue, null));
		when(gateway.enabled()).thenReturn(true);
		when(gateway.comment(anyInt(), anyString(), isNull())).thenReturn(Optional.of(commentId));
		when(identities.status(adminId)).thenReturn(
			new GitHubIdentityService.GitHubIdentityView(true, "equipe", null, null, null, null, false));
	}

	@AfterEach
	void limpar() {
		jdbc.update("delete from support_messages where request_id = ?", requestId);
		jdbc.update("delete from support_requests where id = ?", requestId);
	}

	@Test
	void webhookAntesDaConfirmacaoMantemRespostaOriginalEAutor() {
		SupportMessage original = mensagemDoPainel();
		when(gateway.comment(anyInt(), anyString(), isNull())).thenAnswer(invocacao -> {
			try (var executor = Executors.newSingleThreadExecutor()) {
				assertThat(executor.submit(this::enviarWebhook).get(10, TimeUnit.SECONDS)).isTrue();
			}
			return Optional.of(commentId);
		});

		sync.onReply(evento(original));
		assertThat(enviarWebhook()).isTrue();

		conferirResposta(original);
	}

	@Test
	void webhookDepoisDaConfirmacaoNaoDuplicaAResposta() {
		SupportMessage original = mensagemDoPainel();

		sync.onReply(evento(original));
		assertThat(enviarWebhook()).isTrue();
		assertThat(enviarWebhook()).isTrue();

		conferirResposta(original);
	}

	@Test
	void confirmacaoEsperaWebhookAindaNaoConfirmado() throws Exception {
		SupportMessage original = mensagemDoPainel();
		CountDownLatch pronto = new CountDownLatch(1);
		CountDownLatch liberar = new CountDownLatch(1);
		AtomicInteger sessao = new AtomicInteger();
		when(gateway.comment(anyInt(), anyString(), isNull())).thenAnswer(invocacao -> {
			sessao.set(jdbc.queryForObject("select session_id()", Integer.class));
			return Optional.of(commentId);
		});

		sobrepor(() -> transacoes.executeWithoutResult(estado -> {
			assertThat(enviarWebhook()).isTrue();
			pronto.countDown();
			aguardar(liberar);
		}), () -> sync.onReply(evento(original)), pronto, liberar, sessao);

		conferirResposta(original);
	}

	private SupportMessage mensagemDoPainel() {
		thread.addFromPanel(requestId, "Resposta da equipe.");
		return thread.thread(requestId).get(0);
	}

	private SupportReplyEvent evento(SupportMessage message) {
		return new SupportReplyEvent(requestId, message.id(), adminId, message.body(), null, "Assunto");
	}

	private boolean enviarWebhook() {
		return webhook.applyIssueComment(issue, commentId, "integracao", "Comentário espelhado.");
	}

	private void conferirResposta(SupportMessage original) {
		var conversa = thread.thread(requestId);
		assertThat(conversa).hasSize(1);
		SupportMessage resposta = conversa.get(0);
		assertThat(resposta.id()).isEqualTo(original.id());
		assertThat(resposta.requestId()).isEqualTo(original.requestId());
		assertThat(resposta.direction()).isEqualTo(SupportMessageDirection.ENOIS);
		assertThat(resposta.body()).isEqualTo(original.body());
		assertThat(resposta.createdAt()).isEqualTo(original.createdAt());
		assertThat(resposta.githubCommentId()).isEqualTo(commentId);
		assertThat(resposta.authorName()).isEqualTo("equipe");
		assertThat(resposta.authorGithubLogin()).isEqualTo("equipe");
	}

	private void sobrepor(Runnable primeira, Runnable segunda, CountDownLatch pronto,
			CountDownLatch liberar, AtomicInteger sessao) throws Exception {
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<?> primeiraExecucao = executor.submit(primeira);
			try {
				assertThat(pronto.await(10, TimeUnit.SECONDS)).isTrue();
				Future<?> segundaExecucao = executor.submit(segunda);
				assertThat(esperarBloqueioOuConclusao(segundaExecucao, sessao)).isTrue();
				liberar.countDown();
				primeiraExecucao.get(10, TimeUnit.SECONDS);
				segundaExecucao.get(10, TimeUnit.SECONDS);
			}
			finally {
				liberar.countDown();
			}
		}
	}

	private boolean esperarBloqueioOuConclusao(Future<?> execucao, AtomicInteger sessao)
			throws InterruptedException {
		long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < limite) {
			if (execucao.isDone()) {
				return false;
			}
			if (sessao.get() != 0) {
				Integer bloqueios = jdbc.queryForObject("""
					select count(*) from information_schema.sessions
					where session_id = ? and session_state = 'BLOCKED'
					""", Integer.class, sessao.get());
				if (bloqueios != null && bloqueios > 0) {
					return true;
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("A segunda transação não concluiu nem bloqueou no prazo.");
	}

	private void aguardar(CountDownLatch liberacao) {
		try {
			assertThat(liberacao.await(15, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException erro) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(erro);
		}
	}
}
