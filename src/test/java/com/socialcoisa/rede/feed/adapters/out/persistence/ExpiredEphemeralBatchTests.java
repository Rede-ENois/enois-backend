package com.socialcoisa.rede.feed.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Counters;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostStatus;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.feed.domain.Visibility;
import com.socialcoisa.rede.shared.web.TestDatabaseSupport;

@SpringBootTest
@Transactional
@TestPropertySource(properties =
	"spring.datasource.url=jdbc:h2:mem:expired_ephemeral_batch;MODE=MySQL;DB_CLOSE_DELAY=-1")
class ExpiredEphemeralBatchTests {

	private static final Instant AGORA = Instant.parse("2024-06-01T12:00:00Z");

	@MockitoBean
	private TestDatabaseSupport testDatabaseSupport;

	@Autowired
	private PostRepository posts;

	@PersistenceContext
	private EntityManager entityManager;

	private final String prefixo = "expiry_" + UUID.randomUUID().toString().replace("-", "");

	@Test
	void limitaEntidadesNoBancoEOrdenaPorVencimentoEId() {
		salvar("b", PostType.MOMENT, PostStatus.ACTIVE, AGORA.minusSeconds(300));
		salvar("d", PostType.INSTANTE, PostStatus.ACTIVE, AGORA.minusSeconds(100));
		salvar("a", PostType.INSTANTE, PostStatus.ACTIVE, AGORA.minusSeconds(300));
		salvar("c", PostType.MOMENT, PostStatus.ACTIVE, AGORA.minusSeconds(200));
		salvar("exato", PostType.MOMENT, PostStatus.ACTIVE, AGORA);
		salvar("posterior", PostType.MOMENT, PostStatus.ACTIVE, AGORA.plusSeconds(60));
		salvar("sem_prazo", PostType.MOMENT, PostStatus.ACTIVE, null);
		salvar("apagado", PostType.MOMENT, PostStatus.DELETED, AGORA.minusSeconds(500));
		salvar("fixo", PostType.INSIGHT, PostStatus.ACTIVE, AGORA.minusSeconds(600));
		entityManager.flush();

		List<String> esperados = List.of(prefixo + "_a", prefixo + "_b", prefixo + "_c", prefixo + "_d");
		for (int limite : new int[] { 2, 0, 200 }) {
			entityManager.clear();

			List<Post> lote = posts.findExpiredEphemeral(AGORA, limite);

			int quantidade = Math.min(esperados.size(), Math.max(1, limite));
			assertThat(entityManager.unwrap(Session.class).getStatistics().getEntityCount())
				.as("entidades carregadas para o limite %s", limite)
				.isEqualTo(quantidade);
			assertThat(lote).extracting(post -> post.id().value())
				.containsExactlyElementsOf(esperados.subList(0, quantidade));
		}
	}

	private void salvar(String sufixo, PostType tipo, PostStatus status, Instant vencimento) {
		posts.save(Post.restore(new PostId(prefixo + "_" + sufixo), new UserId(prefixo + "_autor"),
			tipo, "Publicação do cenário", null, List.of(), List.of(), null, AGORA.minusSeconds(3600),
			Visibility.PUBLIC, Counters.zero(), status,
			status == PostStatus.DELETED ? AGORA.minusSeconds(1) : null, null, vencimento));
	}
}
