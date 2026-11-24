package com.socialcoisa.rede.feed.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.feed.application.PollView;
import com.socialcoisa.rede.feed.application.port.in.VotePollUseCase;
import com.socialcoisa.rede.feed.application.port.out.PollRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.PollConfig;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

@SpringBootTest
@TestPropertySource(properties =
	"spring.datasource.url=jdbc:h2:mem:poll_vote_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000")
@Tag("critico")
class PollVoteConcurrencyTests {

	@Autowired private VotePollUseCase votos;
	@Autowired private PostRepository posts;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private TransactionTemplate transacoes;
	@MockitoSpyBean private PollRepository enquetes;
	@MockitoBean private TestDatabaseSupport testDatabaseSupport;

	private final String sufixo = UUID.randomUUID().toString().replace("-", "");
	private final PostId postId = new PostId("poll_corrida_" + sufixo);
	private final UserId pessoa = new UserId("pessoa_corrida_" + sufixo);

	@AfterEach
	void limpar() {
		jdbc.update("delete from poll_votes where post_id = ?", postId.value());
		jdbc.update("delete from poll_options where post_id = ?", postId.value());
		jdbc.update("delete from poll_configs where post_id = ?", postId.value());
		jdbc.update("delete from posts where id = ?", postId.value());
		jdbc.update("delete from users where id = ?", pessoa.value());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void trocasConcorrentesPreservamUmaSelecaoInteira(boolean multipla) throws Exception {
		jdbc.update("""
			insert into users (id, username, name, email, open_to_work, is_mentor, created_at, updated_at, birth_date)
			values (?, ?, 'Participante', ?, false, false, timestamp '2024-01-01 00:00:00',
				timestamp '2024-01-01 00:00:00', date '1990-01-01')
			""", pessoa.value(), pessoa.value(), pessoa.value());
		transacoes.executeWithoutResult(estado -> {
			posts.save(Post.publish(postId, pessoa, PostType.POLL, "Enquete concorrente", null,
				List.of(), List.of(), null, Instant.parse("2024-01-01T00:00:00Z")));
			enquetes.saveOptions(postId, List.of("A", "B", "C"));
			enquetes.saveConfig(postId, new PollConfig(multipla, null));
			enquetes.replaceVotes(postId, pessoa, List.of(2));
		});

		CountDownLatch primeiraSubstituiu = new CountDownLatch(1);
		CountDownLatch liberarPrimeira = new CountDownLatch(1);
		CountDownLatch segundaEntrou = new CountDownLatch(1);
		AtomicReference<Thread> primeiraThread = new AtomicReference<>();
		AtomicInteger segundaSessao = new AtomicInteger();

		doAnswer(invocacao -> {
			if (Thread.currentThread() != primeiraThread.get()) {
				segundaEntrou.countDown();
				return invocacao.callRealMethod();
			}
			Object resultado = invocacao.callRealMethod();
			primeiraSubstituiu.countDown();
			assertThat(liberarPrimeira.await(15, TimeUnit.SECONDS)).isTrue();
			return resultado;
		}).when(enquetes).replaceVotes(eq(postId), eq(pessoa), anyList());

		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<PollView> primeira = executor.submit(() -> {
				primeiraThread.set(Thread.currentThread());
				return votar(multipla, 0);
			});
			assertThat(primeiraSubstituiu.await(10, TimeUnit.SECONDS)).isTrue();

			Future<PollView> segunda = executor.submit(() -> transacoes.execute(estado -> {
				segundaSessao.set(jdbc.queryForObject("select session_id()", Integer.class));
				return votar(multipla, 1);
			}));
			boolean bloqueada = esperarBloqueioOuConclusao(segunda, segundaSessao);
			boolean entrouAntes = segundaEntrou.getCount() == 0;
			liberarPrimeira.countDown();

			PollView primeiroResultado = primeira.get(10, TimeUnit.SECONDS);
			PollView segundoResultado = segunda.get(10, TimeUnit.SECONDS);
			assertThat(bloqueada).isTrue();
			assertThat(entrouAntes).isFalse();
			assertThat(primeiroResultado.myVotes()).containsExactlyElementsOf(selecao(multipla, 0));
			assertThat(segundoResultado.myVotes()).containsExactlyElementsOf(selecao(multipla, 1));
			assertThat(segundoResultado.totalVotes()).isEqualTo(1);
			assertThat(jdbc.queryForList("""
				select option_position from poll_votes
				where post_id = ? and user_id = ? order by option_position
				""", Integer.class, postId.value(), pessoa.value()))
				.containsExactlyElementsOf(selecao(multipla, 1));
		}
		finally {
			liberarPrimeira.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(25, TimeUnit.SECONDS)).isTrue();
		}
	}

	private List<Integer> selecao(boolean multipla, int escolha) {
		return multipla ? List.of(escolha, 2) : List.of(escolha);
	}

	private PollView votar(boolean multipla, int escolha) {
		return votos.vote(postId, pessoa, multipla ? null : escolha, multipla ? selecao(true, escolha) : null);
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
		throw new AssertionError("A segunda transacao nao concluiu nem bloqueou no prazo.");
	}
}
