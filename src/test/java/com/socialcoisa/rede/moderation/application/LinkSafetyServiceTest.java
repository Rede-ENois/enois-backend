package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.moderation.ai.LinkSafetyAnalyzer;
import com.socialcoisa.rede.moderation.ai.ModerationAiService;
import com.socialcoisa.rede.moderation.application.port.out.LinkSafetyCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.LinkedPageReader;
import com.socialcoisa.rede.moderation.domain.LinkSafetyCheck;
import com.socialcoisa.rede.moderation.domain.LinkSafetyVerdict;
import com.socialcoisa.rede.moderation.domain.PageSummary;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

import tools.jackson.databind.ObjectMapper;

/**
 * A barreira de links: o que ela barra sozinha, o que manda para revisão humana e o que ela deixa
 * em paz. O modelo é substituído por um veredito fixo — o que se prova aqui é a DECISÃO do serviço.
 */
class LinkSafetyServiceTest {

	private static final Instant AGORA = Instant.parse("2026-10-29T12:00:00Z");

	private static final Clock CLOCK = Clock.fixed(AGORA, ZoneOffset.UTC);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private PostRepository posts;
	private ModerationRemovePostUseCase removePost;
	private ModeratorService moderators;
	private UserProfileRepository profiles;
	private LinkedPageReader reader;
	private LinkSafetyCheckRepository ledger;
	private final Map<String, PageSummary> paginas = new LinkedHashMap<>();
	private final List<String> removidos = new ArrayList<>();
	private final List<LinkSafetyCheck> gravados = new ArrayList<>();

	@BeforeEach
	void setUp() {
		posts = mock(PostRepository.class);
		removePost = mock(ModerationRemovePostUseCase.class);
		moderators = mock(ModeratorService.class);
		profiles = mock(UserProfileRepository.class);
		ledger = mock(LinkSafetyCheckRepository.class);

		reader = url -> Optional.ofNullable(paginas.get(url.toString()));

		when(ledger.findByUrl(anyString())).thenReturn(Optional.empty());
		when(ledger.save(any())).thenAnswer(inv -> {
			LinkSafetyCheck check = inv.getArgument(0);
			gravados.add(check);
			return check;
		});
		UserProfile autor = mock(UserProfile.class);
		when(autor.username()).thenReturn("mel");
		when(profiles.findById(anyString())).thenReturn(Optional.of(autor));
		when(moderators.sanctionUser(anyString(), anyString(), any(), anyString()))
			.thenReturn(new ModeratorService.SanctionResult("user_1", "mel", "PERMABAN", "ACTIVE"));
	}

	private LinkSafetyService servico(String vereditoDoModelo, boolean enabled) {
		ModerationAiService ai = new ModerationAiService(null, (tipo, id) -> Optional.empty(),
			(tipo, id, limite) -> List.of(), "https://provedor.invalido/v1", "chave-teste", "modelo-teste",
			objectMapper);
		LinkSafetyAnalyzer analyzer = new LinkSafetyAnalyzer(ai, objectMapper) {
			@Override
			public Optional<Verdict> analyze(PageSummary page) {
				return vereditoDoModelo == null ? Optional.empty() : parse(vereditoDoModelo);
			}
		};
		return new LinkSafetyService(ledger, reader, analyzer, posts, removePost, moderators, profiles, CLOCK,
			enabled, 24);
	}

	private void postComLink(String id, String autor, String texto) {
		Post post = mock(Post.class);
		when(post.id()).thenReturn(new PostId(id));
		when(post.content()).thenReturn(texto);
		when(post.pageHtml()).thenReturn(null);
		when(post.authorId()).thenReturn(new com.socialcoisa.rede.feed.domain.UserId(autor));
		when(posts.findById(new PostId(id))).thenReturn(Optional.of(post));
	}

	@Test
	void hostDePlataformaAdultaEDerrubadoSemVisitarNemChamarOModelo() {
		postComLink("post_1", "user_1", "olha isso https://www.pornhub.com/view_video.php?id=1");

		var resultado = servico(null, true).check("post_1");

		assertThat(resultado.status()).isEqualTo("ENFORCED");
		assertThat(resultado.verdict()).isEqualTo(LinkSafetyVerdict.EXPLICIT);
		verify(removePost).removeAsModerator(LinkSafetyService.ACTOR, "post_1", "Automod de link: "
			+ "destino de plataforma de conteudo adulto");
		verify(moderators).sanctionUser(anyString(), anyString(),
			org.mockito.ArgumentMatchers.eq(com.socialcoisa.rede.moderation.domain.SanctionType.PERMABAN),
			anyString());
	}

	@Test
	void linkInnocuoNaoDerrubaNada() {
		postComLink("post_2", "user_1", "meu repositorio https://github.com/melmonfre/rede");
		paginas.put("https://github.com/melmonfre/rede",
			new PageSummary("https://github.com/melmonfre/rede", "melmonfre/rede", "codigo da rede"));

		var resultado = servico("{\"veredito\":\"SAFE\",\"gravidade\":\"LOW\",\"confianca\":0.95,\"motivo\":\"repo\"}",
			true).check("post_2");

		assertThat(resultado.status()).isEqualTo("RECORDED");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
		verify(moderators, never()).sanctionUser(anyString(), anyString(), any(), anyString());
	}

	@Test
	void videoAdultoEmSiteLegitimoEBloqueadoPeloDestino() {
		// O caso do pedido: o dominio é conhecido, o texto é inocente, o vídeo não é.
		postComLink("post_3", "user_1", "assiste ai https://youtube.com/watch?v=abc123");
		paginas.put("https://youtube.com/watch?v=abc123",
			new PageSummary("https://youtube.com/watch?v=abc123", "Video completo - cena adulta explicita",
				"conteudo adulto"));

		var resultado = servico(
			"{\"veredito\":\"EXPLICIT\",\"gravidade\":\"HIGH\",\"confianca\":0.92,\"motivo\":\"video adulto\"}", true)
			.check("post_3");

		assertThat(resultado.status()).isEqualTo("ENFORCED");
		assertThat(resultado.removedPosts()).isEqualTo(1);
		verify(moderators).sanctionUser(anyString(), anyString(),
			org.mockito.ArgumentMatchers.eq(com.socialcoisa.rede.moderation.domain.SanctionType.PERMABAN),
			anyString());
	}

	@Test
	void destinoSuspeitoRestringeSemBanirPermanente() {
		postComLink("post_4", "user_1", "ganhe seguidores https://exemplo.com/seguidores");
		paginas.put("https://exemplo.com/seguidores",
			new PageSummary("https://exemplo.com/seguidores", "Ganhe 10k seguidores", "promocao"));

		var resultado = servico(
			"{\"veredito\":\"SUSPICIOUS\",\"gravidade\":\"MEDIUM\",\"confianca\":0.85,\"motivo\":\"venda de seguidores\"}",
			true).check("post_4");

		assertThat(resultado.status()).isEqualTo("ENFORCED");
		verify(moderators).sanctionUser(anyString(), anyString(),
			org.mockito.ArgumentMatchers.eq(com.socialcoisa.rede.moderation.domain.SanctionType.RESTRICTION),
			anyString());
	}

	@Test
	void abaixoDoPisoSoRegistraParaRevisaoHumana() {
		postComLink("post_5", "user_1", "veja https://youtube.com/watch?v=xyz");
		paginas.put("https://youtube.com/watch?v=xyz",
			new PageSummary("https://youtube.com/watch?v=xyz", "talvez algo", "talvez"));

		var resultado = servico(
			"{\"veredito\":\"EXPLICIT\",\"gravidade\":\"HIGH\",\"confianca\":0.45,\"motivo\":\"nao tenho certeza\"}",
			true).check("post_5");

		assertThat(resultado.status()).isEqualTo("RECORDED");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
		assertThat(gravados).hasSize(1);
	}

	@Test
	void destinoSemDescricaoNaoViraAcusacao() {
		postComLink("post_6", "user_1", "veja https://exemplo.com/fora-do-ar");

		var resultado = servico("{\"veredito\":\"EXPLICIT\",\"confianca\":0.99,\"motivo\":\"x\"}", true)
			.check("post_6");

		assertThat(resultado.status()).isEqualTo("NO_VERDICT");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
		assertThat(gravados).hasSize(1);
		assertThat(gravados.get(0).verdict()).isEqualTo(LinkSafetyVerdict.UNSURE);
	}

	@Test
	void destinoJaJulgadoNaoEVisitadoDeNovo() {
		postComLink("post_7", "user_1", "veja https://youtube.com/watch?v=ja-julgado");
		when(ledger.findByUrl("https://youtube.com/watch?v=ja-julgado")).thenReturn(Optional.of(LinkSafetyCheck.of(
			"https://youtube.com/watch?v=ja-julgado", "user_1", LinkSafetyVerdict.SAFE, "LOW", 0.9, "ok",
			"modelo", AGORA)));

		var resultado = servico("{\"veredito\":\"EXPLICIT\",\"confianca\":0.99,\"motivo\":\"x\"}", true)
			.check("post_7");

		assertThat(resultado.status()).isEqualTo("RECORDED");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
	}

	@Test
	void postSemLinkNaoGastaNada() {
		postComLink("post_8", "user_1", "post sem endereco nenhum");

		var resultado = servico("{\"veredito\":\"SAFE\",\"confianca\":0.9}", true).check("post_8");

		assertThat(resultado.status()).isEqualTo("NO_LINKS");
		assertThat(gravados).isEmpty();
	}

	@Test
	void desligadaNaoFazNada() {
		postComLink("post_9", "user_1", "https://www.pornhub.com/x");

		var resultado = servico(null, false).check("post_9");

		assertThat(resultado.status()).isEqualTo("DISABLED");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
	}

	@Test
	void postInexistenteNaoQuebra() {
		when(posts.findById(any())).thenReturn(Optional.empty());

		assertThat(servico(null, true).check("post_sumiu").status()).isEqualTo("UNKNOWN_POST");
	}

	@Test
	void derrubaTambemOsOutrosPostsDoAutorQueApontamParaOMesmoDestino() {
		postComLink("post_10", "user_1", "veja https://www.pornhub.com/x");
		Post outro = mock(Post.class);
		when(outro.id()).thenReturn(new PostId("post_11"));
		when(outro.content()).thenReturn("de novo https://pornhub.com/x");
		when(outro.pageHtml()).thenReturn(null);
		when(posts.findAllByAuthor(any())).thenReturn(List.of(outro));

		var resultado = servico(null, true).check("post_10");

		// O post que disparou e o outro que aponta para o mesmo destino.
		assertThat(resultado.removedPosts()).isEqualTo(2);
		verify(removePost).removeAsModerator(org.mockito.ArgumentMatchers.eq(LinkSafetyService.ACTOR),
			org.mockito.ArgumentMatchers.eq("post_10"), anyString());
		verify(removePost).removeAsModerator(LinkSafetyService.ACTOR, "post_11",
			"Automod de link: destino de plataforma de conteudo adulto");
	}

}
