package com.socialcoisa.rede.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
import com.socialcoisa.rede.feed.application.port.out.PollRepository;
import com.socialcoisa.rede.feed.application.port.out.PostCommunityReader;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.identity.application.port.out.IdentityVerificationRepository;
import com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;

class SavedFeedOrderingTests {

	private static final Instant AGORA = Instant.parse("2024-06-01T12:00:00Z");
	private static final UserId LEITOR = new UserId("saved_reader");

	private final PostRepository posts = mock(PostRepository.class);
	private final PostInteractionRepository interactions = mock(PostInteractionRepository.class);
	private final GetFeedService feed = new GetFeedService(posts, null, null, null,
		mock(UserProfileRepository.class), mock(UserSealRepository.class), interactions, null, null,
		mock(BlockedUsersReader.class), mock(PostCommunityReader.class), null, null, null, null,
		mock(PostNoteService.class), mock(PollRepository.class), mock(PostRepostRepository.class),
		mock(GetMediaSummariesUseCase.class),
		mock(com.socialcoisa.rede.shared.sanctions.SanctionGateReader.class),
		mock(IdentityVerificationRepository.class),
		mock(com.socialcoisa.rede.doacoes.application.port.out.DoacaoRepository.class),
		Clock.fixed(AGORA, ZoneOffset.UTC));

	@Test
	void preservaAOrdemSemPercorrerOsEnriquecidosParaCadaId() {
		Map<String, PostView> views = new LinkedHashMap<>();
		List<Post> carregados = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			String id = "saved_post_" + i;
			PostView view = mock(PostView.class, RETURNS_SELF);
			when(view.id()).thenReturn(id);
			views.put(id, view);
			carregados.add(post(id));
		}
		Post excluido = post("saved_deleted");
		excluido.delete(AGORA);
		carregados.add(excluido);

		List<String> salvos = new ArrayList<>(views.keySet());
		Collections.reverse(salvos);
		salvos.add(2, "saved_missing");
		salvos.add("saved_deleted");
		when(interactions.findSavedPostIdsByUserId(LEITOR)).thenReturn(salvos);
		when(posts.findByIds(any())).thenReturn(carregados);

		List<PostView> esperados = new ArrayList<>(views.values()).reversed();
		try (var fabrica = mockStatic(PostView.class)) {
			fabrica.when(() -> PostView.from(any(Post.class), any(), any(), any(), anyList()))
				.thenAnswer(chamada -> {
					Post post = chamada.getArgument(0);
					return views.get(post.id().value());
				});

			FeedPage pagina = feed.getSavedFeed(LEITOR);

			assertThat(pagina.items()).containsExactlyElementsOf(esperados);
			assertThat(pagina.nextCursor()).isNull();
			for (PostView view : views.values()) {
				verify(view, atMostOnce()).id();
			}
		}
	}

	@Test
	void listaVaziaNaoBuscaPosts() {
		when(interactions.findSavedPostIdsByUserId(LEITOR)).thenReturn(List.of());

		FeedPage pagina = feed.getSavedFeed(LEITOR);

		assertThat(pagina.items()).isEmpty();
		assertThat(pagina.nextCursor()).isNull();
		verifyNoInteractions(posts);
	}

	private static Post post(String id) {
		return Post.publish(new PostId(id), new UserId("saved_author"), PostType.INSIGHT,
			"Publicação do cenário", null, List.of(), List.of(), null, AGORA.minusSeconds(60));
	}
}
