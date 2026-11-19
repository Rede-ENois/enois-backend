package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.GetDiarioUseCase;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepostRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostRepost;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Monta o diario de atividade do perfil a partir dos dados que ja existem: posts do autor,
 * reposts e (para o dono) comentarios ativos. Tres consultas por fonte com janela maior que a
 * pagina, mesclagem por data e cursor pelo ultimo evento — o mesmo esquete da listagem do perfil.
 */
@Service
public class GetDiarioService implements GetDiarioUseCase {

	private static final Instant DEFAULT_BEFORE = Instant.parse("9999-12-31T23:59:59Z");

	/** Eventos sao leves (excerto, sem enriquecimento), entao a pagina pode ser maior que a do feed. */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 50;

	/** Cada fonte busca uma janela maior que a pagina pra mesclagem nao perder eventos recentes. */
	private static final int JANELA_MULTIPLICADOR = 2;

	/** Tamanho do excerto de conteudo proprio e do contexto do post original. */
	private static final int TRECHO_TAMANHO = 140;

	private final PostRepository postRepository;
	private final PostRepostRepository postReposts;
	private final CommentRepository commentRepository;
	private final UserProfileRepository userProfileRepository;
	private final SanctionGateReader sanctionGate;
	private final CursorCodec cursorCodec;

	public GetDiarioService(PostRepository postRepository, PostRepostRepository postReposts,
			CommentRepository commentRepository, UserProfileRepository userProfileRepository,
			SanctionGateReader sanctionGate, CursorCodec cursorCodec) {
		this.postRepository = postRepository;
		this.postReposts = postReposts;
		this.commentRepository = commentRepository;
		this.userProfileRepository = userProfileRepository;
		this.sanctionGate = sanctionGate;
		this.cursorCodec = cursorCodec;
	}

	@Override
	@Transactional(readOnly = true)
	public DiarioPage getDiario(UserId viewerId, String username, Optional<Instant> before, int limit) {
		int tamanho = normalizeLimit(limit);
		Instant antes = before.orElse(DEFAULT_BEFORE);
		String autorId = userProfileRepository.findByUsername(username)
			.map(UserProfile::id)
			.orElse(username);
		if (sanctionGate.banned(autorId)) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		boolean dono = viewerId != null && viewerId.value().equals(autorId);

		int janela = tamanho * JANELA_MULTIPLICADOR;
		List<DiarioEventoView> eventos = new ArrayList<>();
		List<Post> authored = postRepository.findActiveByAuthor(new UserId(autorId), antes, janela);
		for (Post post : authored) {
			eventos.add(new DiarioEventoView("PUBLICACAO", post.createdAt(), post.id().value(),
				excerto(post.content()), null));
		}

		List<PostRepost> reposts = postReposts.findByUserId(autorId, antes, janela);
		Map<String, Post> originais = postsByIds(reposts.stream().map(PostRepost::postId).toList());
		for (PostRepost repost : reposts) {
			// Repost de post apagado nao tem pra onde levar: some do diario, como na aba Reposts.
			Post original = originais.get(repost.postId());
			if (original == null || !original.isActive()) {
				continue;
			}
			eventos.add(new DiarioEventoView("REPOST", repost.createdAt(), repost.postId(),
				excerto(original.content()), contextoDe(original)));
		}

		// Comentarios no diario sao so do dono: mesma regra de privacidade da aba de respostas.
		List<Comment> comentarios = List.of();
		if (dono) {
			comentarios = commentRepository.findByAuthorPaged(autorId, antes, janela);
			Map<String, Post> pais = postsByIds(comentarios.stream().map(c -> c.postId().value()).toList());
			for (Comment comentario : comentarios) {
				Post pai = pais.get(comentario.postId().value());
				DiarioEventoView.Contexto contexto = pai != null && pai.isActive()
					? contextoDe(pai) : null;
				eventos.add(new DiarioEventoView("COMENTARIO", comentario.createdAt(),
					comentario.postId().value(), excerto(comentario.content()), contexto));
			}
		}

		eventos.sort(Comparator.comparing(DiarioEventoView::quando).reversed());
		List<DiarioEventoView> pagina = eventos.stream().limit(tamanho).toList();
		if (pagina.isEmpty()) {
			return new DiarioPage(List.of(), null);
		}
		Instant ultima = pagina.get(pagina.size() - 1).quando();
		// Pagina cheia = pode haver mais eventos nas fontes (a janela por fonte e o dobro da pagina,
		// entao a mesclagem enche antes da janela); pagina menor significa fim do diario.
		String nextCursor = pagina.size() == tamanho ? cursorCodec.encode(ultima) : null;
		return new DiarioPage(pagina, nextCursor);
	}

	private Map<String, Post> postsByIds(List<String> ids) {
		List<String> distintos = ids.stream().distinct().toList();
		if (distintos.isEmpty()) {
			return Map.of();
		}
		return postRepository.findByIds(distintos.stream().map(PostId::new).toList()).stream()
			.collect(Collectors.toMap(p -> p.id().value(), Function.identity()));
	}

	private DiarioEventoView.Contexto contextoDe(Post original) {
		UserProfile autor = userProfileRepository.findById(original.authorId().value()).orElse(null);
		return new DiarioEventoView.Contexto(
			autor != null ? autor.displayWithNickname() : null,
			autor != null ? autor.username() : null,
			excerto(original.content()));
	}

	private String excerto(String conteudo) {
		if (conteudo == null) {
			return null;
		}
		return conteudo.length() > TRECHO_TAMANHO ? conteudo.substring(0, TRECHO_TAMANHO) + "..." : conteudo;
	}

	private int normalizeLimit(int limit) {
		if (limit < 1) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(limit, MAX_PAGE_SIZE);
	}
}
