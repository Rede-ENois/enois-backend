package com.socialcoisa.rede.linkpreview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.blog.application.BlogService;
import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.events.application.EventService;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.port.in.GetPostUseCase;
import com.socialcoisa.rede.games.application.GameService;
import com.socialcoisa.rede.jobs.application.JobService;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O resumo das paginas internas.
 *
 * <p>O cartao de um link interno mostra o que a pagina mostra: post pela mesma leitura publica,
 * perfil pelos dados do perfil, vaga pela vaga. Pagina que nao existe (404) nao e erro do post —
 * e um link sem cartao.</p>
 */
class InternalPageResolverTests {

	private final GetPostUseCase posts = mock(GetPostUseCase.class);

	private final UserProfileRepository profiles = mock(UserProfileRepository.class);

	private final BlogService blogs = mock(BlogService.class);

	private final JobService jobs = mock(JobService.class);

	private final CommunityService communities = mock(CommunityService.class);

	private final EventService events = mock(EventService.class);

	private final GameService games = mock(GameService.class);

	private InternalPageResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new InternalPageResolver(posts, profiles, blogs, jobs, communities, events, games);
	}

	@Test
	void postInternoUsaALeituraPublicaDoPost() {
		PostView post = mock(PostView.class);
		when(post.authorName()).thenReturn("Mel");
		when(post.content()).thenReturn("Um texto sobre **testes**");
		when(post.mediaIds()).thenReturn(List.of("media_1"));
		when(posts.getPost("11111111-1111-1111-1111-111111111111", null)).thenReturn(post);

		Optional<LinkPreview> preview = resolver
			.resolve("/post/11111111-1111-1111-1111-111111111111", null);

		assertThat(preview).isPresent();
		assertThat(preview.get().title()).isEqualTo("Mel no Énóis");
		assertThat(preview.get().description()).isEqualTo("Um texto sobre testes");
		assertThat(preview.get().imageUrl()).isEqualTo("/api/media/media_1");
		assertThat(preview.get().siteName()).isEqualTo("Post");
	}

	@Test
	void postDeCanalPrivadoInvisivelParaQuemNaoEMembroNaoViraCartao() {
		// A leitura do post responde 404 para quem nao e membro; o cartao segue a mesma regra.
		when(posts.getPost(anyString(), any()))
			.thenThrow(new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));

		assertThat(resolver.resolve("/post/11111111-1111-1111-1111-111111111111", null)).isEmpty();
	}

	@Test
	void idDePostQueNaoEPareceIdNaoConsultaOBanco() {
		assertThat(resolver.resolve("/post/qualquer-coisa", null)).isEmpty();
		verify(posts, never()).getPost(anyString(), any());
	}

	@Test
	void perfilUsaNomeHeadlineEFoto() {
		UserProfile profile = mock(UserProfile.class);
		when(profile.name()).thenReturn("Mel Monfre");
		when(profile.headline()).thenReturn("Desenvolvedora");
		when(profile.banner()).thenReturn("/media/banner.png");
		when(profiles.findByUsername("mel")).thenReturn(Optional.of(profile));

		Optional<LinkPreview> preview = resolver.resolve("/perfil/mel", null);

		assertThat(preview).isPresent();
		assertThat(preview.get().title()).isEqualTo("Mel Monfre");
		assertThat(preview.get().description()).isEqualTo("Desenvolvedora");
		assertThat(preview.get().imageUrl()).isEqualTo("/media/banner.png");
		assertThat(preview.get().siteName()).isEqualTo("Perfil");
	}

	@Test
	void perfilSemHeadlineFicaSemDescricao() {
		UserProfile profile = mock(UserProfile.class);
		when(profile.name()).thenReturn("Mel");
		when(profile.headline()).thenReturn("  ");
		when(profiles.findByUsername("mel")).thenReturn(Optional.of(profile));

		LinkPreview preview = resolver.resolve("/perfil/mel", null).orElseThrow();

		assertThat(preview.description()).isNull();
		assertThat(preview.imageUrl()).isNull();
	}

	@Test
	void perfilQueNaoExisteNaoViraCartao() {
		when(profiles.findByUsername("fantasma")).thenReturn(Optional.empty());

		assertThat(resolver.resolve("/perfil/fantasma", null)).isEmpty();
	}

	@Test
	void comunidadeUsaNomeDescricaoECapa() {
		Community community = mock(Community.class);
		when(community.name()).thenReturn("Devs BR");
		when(community.description()).thenReturn("Comunidade de programacao");
		when(community.cover()).thenReturn("/media/capa.png");
		when(communities.getById("c1")).thenReturn(community);

		LinkPreview preview = resolver.resolve("/comunidades/c1", null).orElseThrow();

		assertThat(preview.title()).isEqualTo("Devs BR");
		assertThat(preview.description()).isEqualTo("Comunidade de programacao");
		assertThat(preview.imageUrl()).isEqualTo("/media/capa.png");
	}

	@Test
	void vagaUsaTituloEDescricaoSemMarkup() {
		com.socialcoisa.rede.jobs.domain.Job job = mock(com.socialcoisa.rede.jobs.domain.Job.class);
		when(job.title()).thenReturn("Dev Back-end");
		when(job.description()).thenReturn("<p>Java <b>e</b> Spring</p>");
		when(job.logo()).thenReturn("/media/logo.png");
		when(jobs.getById("j1")).thenReturn(job);

		LinkPreview preview = resolver.resolve("/vagas/j1", null).orElseThrow();

		assertThat(preview.title()).isEqualTo("Dev Back-end");
		assertThat(preview.description()).isEqualTo("Java e Spring");
		assertThat(preview.imageUrl()).isEqualTo("/media/logo.png");
	}

	@Test
	void paginaDeComunidadeNaoConfundeComCanal() {
		assertThat(resolver.resolve("/comunidades/c1/canais/canal1", null)).isEmpty();
		verify(communities, never()).getById(anyString());
	}

	@Test
	void rotaDesconhecidaNaoTemCartao() {
		assertThat(resolver.resolve("/feed", null)).isEmpty();
		assertThat(resolver.resolve("/mensagens", null)).isEmpty();
		assertThat(resolver.resolve("/", null)).isEmpty();
		assertThat(resolver.resolve("", null)).isEmpty();
	}

	@Test
	void oCaminhoIgnoraQueryEPontoFinal() {
		UserProfile profile = mock(UserProfile.class);
		when(profile.name()).thenReturn("Mel");
		when(profiles.findByUsername("mel")).thenReturn(Optional.of(profile));

		assertThat(resolver.resolve("/perfil/mel?utm_source=x#bio", null)).isPresent();
	}

	@Test
	void recusaDeAcessoNaoViraErroDoPost() {
		when(communities.getById("c1")).thenThrow(new ApiException(404, "COMMUNITY_NOT_FOUND", "Nao encontrada."));

		assertThat(resolver.resolve("/comunidades/c1", null)).isEmpty();
	}

	@Test
	void leituraDoPostUsaOLeitorDaSessao() {
		PostView post = mock(PostView.class);
		when(post.authorName()).thenReturn("Mel");
		when(post.content()).thenReturn("Oi");
		when(posts.getPost(anyString(), any())).thenReturn(post);

		resolver.resolve("/post/11111111-1111-1111-1111-111111111111", "user_1");

		verify(posts).getPost(anyString(), any());
		verify(posts, never()).getPost(anyString(), isNull());
	}
}
