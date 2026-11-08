package com.socialcoisa.rede.feed.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;

/**
 * O indice de {@code comments (author_id, post_id)} existe para as abas UNSEEN e INTERACTED
 * (issue #521) e NAO pode mudar o que elas devolvem: e otimizacao pura.
 *
 * <p>Este teste trava as duas coisas: o indice esta no schema (a migracao V256 rodou — o
 * schema-check do CI valida contra MariaDB real) e as consultas que ele serve continuam
 * devolvendo o mesmo conjunto, com os mesmos filtros. Se alguem trocar a ordem das colunas do
 * indice ou a forma da consulta, o teste avisa.</p>
 */
@SpringBootTest
class FeedInteractionIndexTests {

	@Autowired
	private PostRepository posts;

	@Autowired
	private CommentRepository comments;

	@Autowired
	private PostInteractionRepository interactions;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void indiceDeComentariosPorAutorExiste() {
		// information_schema diverge entre H2 e MariaDB; a visao INDEX_COLUMNS e a que o H2
		// expoe com as colunas de cada indice, na ordem delas.
		List<String> colunas = jdbc.queryForList("""
			select column_name from information_schema.index_columns
			where table_name = 'COMMENTS' and index_name = 'IDX_COMMENTS_AUTHOR_POST'
			order by ordinal_position
			""", String.class);

		// A ordem importa: a busca parte do leitor (author_id) e o post_id fecha o par.
		assertThat(colunas).containsExactly("AUTHOR_ID", "POST_ID");
	}

	/**
	 * A consulta da aba UNSEEN, na forma que o repositorio gera: o post entra quando o leitor nao
	 * reagiu nem comentou. O indice nao pode mudar o resultado — so o plano.
	 */
	@Test
	void unseenContinuaDevolvendoOsMesmosPostsComOIndice() {
		UserId leitor = new UserId("user_idx_leitor_" + sufixo());
		UserId autor = new UserId("user_idx_autor_" + sufixo());
		PostId reagido = new PostId("post_idx_reagido_" + sufixo());
		PostId comentado = new PostId("post_idx_comentado_" + sufixo());
		PostId intacto = new PostId("post_idx_intacto_" + sufixo());

		salvarPosts(autor, reagido, comentado, intacto);
		registrarLeitura(leitor, reagido, comentado);

		List<Post> vistos = posts.findActiveUnseen(leitor, List.of(), Instant.now().plusSeconds(60), 50);

		assertThat(ids(vistos)).contains(intacto.value()).doesNotContain(reagido.value(), comentado.value());
	}

	/** A aba INTERACTED: entra o post em que o leitor reagiu OU comentou. */
	@Test
	void interactedContinuaDevolvendoOsMesmosPostsComOIndice() {
		UserId leitor = new UserId("user_idx2_leitor_" + sufixo());
		UserId autor = new UserId("user_idx2_autor_" + sufixo());
		PostId reagido = new PostId("post_idx2_reagido_" + sufixo());
		PostId comentado = new PostId("post_idx2_comentado_" + sufixo());
		PostId intacto = new PostId("post_idx2_intacto_" + sufixo());

		salvarPosts(autor, reagido, comentado, intacto);
		registrarLeitura(leitor, reagido, comentado);

		List<Post> vistos = posts.findActiveInteracted(leitor, Instant.now().plusSeconds(60), 50);

		assertThat(ids(vistos)).contains(reagido.value(), comentado.value()).doesNotContain(intacto.value());
	}

	private void salvarPosts(UserId autor, PostId... ids) {
		Instant agora = Instant.now();
		for (PostId id : ids) {
			posts.save(Post.publish(id, autor, PostType.INSIGHT, "Post do teste de indice.", null,
				List.of(), List.of(), null, null, agora, null));
		}
	}

	private void registrarLeitura(UserId leitor, PostId reagido, PostId comentado) {
		// A reacao e read-decide-write na tabela de interacoes (docs/CONCURRENCY.md): dentro de
		// transacao propria, como os servicos de producao fazem.
		transactions.executeWithoutResult(status -> interactions.addReaction(
			"reaction_idx_" + sufixo(), reagido, leitor, ReactionType.LIKE, Instant.now()));
		comments.save(Comment.create("comment_idx_" + sufixo(), comentado, leitor, null,
			"Comentario do leitor.", List.of(), Instant.now()));
	}

	private List<String> ids(List<Post> posts) {
		return posts.stream().map(p -> p.id().value()).toList();
	}

	private static String sufixo() {
		return UUID.randomUUID().toString().substring(0, 8);
	}
}
