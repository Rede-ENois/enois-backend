package com.socialcoisa.rede.feed.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;

/**
 * A aba "Ainda nao vi" filtra por anti-join. A troca de {@code NOT EXISTS} por
 * {@code LEFT JOIN ... IS NULL} e OTIMIZACAO: o conjunto tem de ser exatamente o mesmo.
 *
 * <p>Este teste trava o comportamento, nao o plano: reage/nao reage, comenta/nao comenta, post
 * proprio, post de outro tipo, tópico bloqueado, janela de data e o comportamento quando a MESMA
 * pessoa comenta varias vezes no mesmo post (o join nao pode multiplicar a linha nem deixar de
 * filtrar). Se alguem mexer na consulta e o conjunto mudar, o teste avisa.</p>
 */
@SpringBootTest
class UnseenFeedQueryEquivalenceTests {

	@Autowired
	private PostRepository posts;

	@Autowired
	private JdbcTemplate jdbc;

	private final String prefix = "unseen_eq_" + UUID.randomUUID().toString().substring(0, 8);

	private String readerId;
	private String authorId;

	@Test
	void unseenReturnsExactlyThePostsWithoutReactionNorComment() {
		setUpPeople();
		String reagiu = insertPost("reagiu");
		String comentou = insertPost("comentou");
		String intocado = insertPost("intocado");
		String proprio = insertPostBy(readerId, "proprio");
		String blog = insertPost("blog", PostType.BLOG);
		react(reagiu);
		comment(comentou);

		List<String> ids = unseenIds(50);

		assertThat(ids)
			.as("o post intocado entra; os que o leitor tocou, o proprio e o BLOG nao")
			.contains(intocado)
			.doesNotContain(reagiu, comentou, proprio, blog);
	}

	@Test
	void repeatedCommentsFromTheSameReaderDoNotLeakThePost() {
		setUpPeople();
		String comentou = insertPost("muitos_comentarios");
		for (int i = 0; i < 5; i++) {
			comment(comentou);
		}
		String intocado = insertPost("intocado_ao_lado");

		List<String> ids = unseenIds(50);

		assertThat(ids).as("comentario repetido filtra igual a um so, e sem multiplicar linha")
			.doesNotContain(comentou).contains(intocado);
	}

	@Test
	void reactThenUnreactMakesThePostUnseenAgain() {
		setUpPeople();
		String post = insertPost("reagiu_e_desfez");
		react(post);

		assertThat(unseenIds(50)).as("enquanto reagiu, fica fora").doesNotContain(post);

		jdbc.update("delete from reactions where post_id = ? and user_id = ?", post, readerId);

		assertThat(unseenIds(50)).as("desfeita a reacao, volta para a aba").contains(post);
	}

	@Test
	void blockedTopicStaysOutAndPostWithoutTopicStaysIn() {
		setUpPeople();
		String bloqueado = insertPost("topico_bloqueado");
		jdbc.update("update posts set topic = ? where id = ?", "TOPICO_BLOQ", bloqueado);
		String semTopico = insertPost("sem_topico");

		List<String> ids = posts.findActiveUnseen(new UserId(readerId), List.of("TOPICO_BLOQ"),
			java.time.Instant.now().plusSeconds(3600), 50).stream().map(post -> post.id().value()).toList();

		assertThat(ids).as("topico bloqueado sai, post sem topico fica")
			.doesNotContain(bloqueado).contains(semTopico);
	}

	@Test
	void theWindowCutsPostsNewerThanTheCursor() {
		setUpPeople();
		String antigo = insertPost("antigo");
		jdbc.update("update posts set created_at = ? where id = ?",
			java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7200)), antigo);
		String novo = insertPost("novo");

		List<String> ids = posts.findActiveUnseen(new UserId(readerId), List.of(), 
			java.time.Instant.now().minusSeconds(3600), 50).stream().map(post -> post.id().value()).toList();

		assertThat(ids).as("o cursor corta o que e mais novo que ele")
			.contains(antigo).doesNotContain(novo);
	}

	private List<String> unseenIds(int limit) {
		return posts.findActiveUnseen(new UserId(readerId), List.of(),
			java.time.Instant.now().plusSeconds(3600), limit).stream()
			.map(post -> post.id().value()).toList();
	}

	private void setUpPeople() {
		readerId = prefix + "_reader";
		authorId = prefix + "_author";
		for (String id : List.of(readerId, authorId)) {
			jdbc.update("""
				merge into users (id, username, name, email, open_to_work, is_mentor, created_at, updated_at)
				key(id) values (?, ?, 'Filtro', ?, false, false, now(), now())
				""", id, id, id + "@test.com");
		}
	}

	private String insertPost(String suffix) {
		return insertPostBy(authorId, suffix);
	}

	private String insertPost(String suffix, PostType type) {
		return insertPostBy(authorId, suffix, type);
	}

	private String insertPostBy(String author, String suffix) {
		return insertPostBy(author, suffix, PostType.INSIGHT);
	}

	private String insertPostBy(String author, String suffix, PostType type) {
		String id = prefix + "_post_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 6);
		jdbc.update("""
			insert into posts (id, author_id, type, content, created_at, visibility, reactions, comments,
				saves, shares, status)
			values (?, ?, ?, ?, now(), 'PUBLIC', 0, 0, 0, 0, 'ACTIVE')
			""", id, author, type.name(), "conteudo " + suffix);
		return id;
	}

	private void react(String postId) {
		jdbc.update("""
			insert into reactions (id, post_id, user_id, reaction_type, created_at)
			values (?, ?, ?, 'LIKE', now())
			""", prefix + "_reac_" + UUID.randomUUID().toString().substring(0, 6), postId, readerId);
	}

	private void comment(String postId) {
		jdbc.update("""
			insert into comments (id, post_id, author_id, content, created_at, status)
			values (?, ?, ?, 'comentario', now(), 'ACTIVE')
			""", prefix + "_com_" + UUID.randomUUID().toString().substring(0, 6), postId, readerId);
	}
}