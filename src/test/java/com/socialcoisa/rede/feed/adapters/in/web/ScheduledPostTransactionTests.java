package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.feed.application.ScheduledPostService;
import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.application.port.out.ScheduledPostRepository;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ScheduledPost;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "rede.rate.duplicate-window-minutes=5")
@AutoConfigureMockMvc
class ScheduledPostTransactionTests {

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private ScheduledPostService service;
	@Autowired private PostRepository posts;
	@Autowired private JdbcTemplate jdbc;
	@MockitoSpyBean private ScheduledPostRepository scheduledPosts;
	@MockitoSpyBean private PublishPostUseCase publisher;

	private final List<String> agendamentos = new ArrayList<>();

	@AfterEach
	void limpar() {
		for (String id : agendamentos) {
			jdbc.update("delete from scheduled_posts where id = ?", id);
		}
	}

	@Test
	void falhaNaRemocaoDesfazPublicacaoERegistraTentativa() throws Exception {
		ScheduledPost item = item("Publicacao com remocao recusada");
		doReturn(List.of(item)).when(scheduledPosts).findDue(any(Instant.class), eq(3), eq(20));
		doAnswer(invocation -> {
			invocation.callRealMethod();
			throw new IllegalStateException("Falha na remocao");
		}).when(scheduledPosts).delete(item.id());

		service.publishDue();

		assertThat(scheduledPosts.findById(item.id()).orElseThrow().attempts()).isEqualTo(1);
		assertThat(posts.findAllByAuthor(new UserId(item.userId())))
			.noneMatch(post -> item.content().equals(post.content()));
	}

	@Test
	void duasLeiturasDoMesmoItemPublicamUmaVez() throws Exception {
		ScheduledPost item = item("Publicacao concorrente");
		CountDownLatch leituras = new CountDownLatch(2);
		doAnswer(invocation -> {
			leituras.countDown();
			assertThat(leituras.await(10, TimeUnit.SECONDS)).isTrue();
			return List.of(item);
		}).when(scheduledPosts).findDue(any(Instant.class), eq(3), eq(20));

		var pool = Executors.newFixedThreadPool(2);
		try {
			var primeira = pool.submit(service::publishDue);
			var segunda = pool.submit(service::publishDue);
			assertThat(primeira.get(20, TimeUnit.SECONDS) + segunda.get(20, TimeUnit.SECONDS)).isEqualTo(1);
		}
		finally {
			leituras.countDown();
			leituras.countDown();
			pool.shutdownNow();
			assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(scheduledPosts.findById(item.id())).isEmpty();
		assertThat(posts.findAllByAuthor(new UserId(item.userId())))
			.filteredOn(post -> item.content().equals(post.content())).hasSize(1);
		verify(publisher, times(1)).publish(any());
	}

	private ScheduledPost item(String content) throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		String userId = new TestAuthHelper(mockMvc, objectMapper)
			.authenticate("sched_tx_" + suffix.substring(0, 8)).userId();
		Instant passado = Instant.parse("2024-01-01T00:00:00Z");
		ScheduledPost item = new ScheduledPost("sched_tx_" + suffix, userId, PostType.INSIGHT, content,
			null, null, List.of(), List.of(), List.of(), null, "TECNOLOGIA", List.of(),
			passado, passado, null, 0);
		agendamentos.add(item.id());
		return scheduledPosts.save(item);
	}
}
