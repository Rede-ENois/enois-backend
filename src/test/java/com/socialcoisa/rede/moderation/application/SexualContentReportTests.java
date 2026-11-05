package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialcoisa.rede.feed.application.port.in.ModerationQuarantinePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.moderation.ai.ModerationAiService;
import com.socialcoisa.rede.moderation.application.port.out.ContentAutomationCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedMediaReader;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck.ReviewStatus;
import com.socialcoisa.rede.notifications.application.NotificationService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Denúncia por conteúdo sexual em post: o post sai do ar na hora e o bot decide primeiro.
 *
 * <p>O contrato são três saídas e só elas — NSFW confirmado apaga, seguro devolve ao feed, dúvida
 * fica com uma pessoa — e nenhuma delas pune a conta. O que os casos abaixo travam é justamente o
 * que não pode acontecer: apagar sem confirmação, devolver sem certeza, ou deixar o caso sumir da
 * fila humana.</p>
 */
@ExtendWith(MockitoExtension.class)
class SexualContentReportTests {

	private static final String POST_ID = "post_sexual_tests";
	private static final String USER = "user_sexual_tests";
	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

	@Mock
	private ContentAutomationCheckRepository checks;

	@Mock
	private PostRepository posts;

	@Mock
	private ModerationQuarantinePostUseCase quarantinePost;

	@Mock
	private ModerationRemovePostUseCase removePost;

	@Mock
	private ReportedMediaReader mediaReader;

	@Mock
	private ModerationAiService ai;

	@Mock
	private NotificationService notifications;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private SexualContentReportService service;

	@BeforeEach
	void setUp() {
		service = new SexualContentReportService(checks, posts, quarantinePost, removePost, mediaReader, ai,
			notifications, Clock.fixed(NOW, ZoneOffset.UTC), true);
		// Lenient: o caso desligado e o já avaliado nem chegam a consultar o livro-caixa.
		org.mockito.Mockito.lenient().when(checks.findByPost(POST_ID)).thenReturn(Optional.empty());
	}

	@Test
	void nsfwConfirmadoApagaOPostDeVez() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		giveVerdict("PENALIZAR", 0.95, false);

		var outcome = service.handle(POST_ID, "foto explicita");

		assertThat(outcome.status()).isEqualTo("REMOVED");
		assertThat(outcome.removed()).isTrue();
		assertThat(outcome.released()).isFalse();
		verify(removePost).removeAsModerator(eq(SexualContentReportService.ACTOR), eq(POST_ID),
			contains("conteudo explicito"));
		verify(checks).updateStatus(POST_ID, ReviewStatus.REMOVED);
	}

	@Test
	void oPostSaiDoArAntesDoBotDecidir() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		giveVerdict("PENALIZAR", 0.95, false);

		service.handle(POST_ID, "foto explicita");

		// A precaução é imediata: esconder vem antes de qualquer chamada ao modelo.
		InOrder ordem = inOrder(quarantinePost, ai);
		ordem.verify(quarantinePost).quarantine(anyString(), eq(POST_ID), anyString());
		ordem.verify(ai).triage(anyString(), eq("SEXUAL_CONTENT"), anyString(), anyList());
	}

	@Test
	void oCasoEntraNaFilaHumanaAntesDeOuvirOBot() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		giveVerdict("PENALIZAR", 0.95, false);

		service.handle(POST_ID, "foto explicita");

		ArgumentCaptor<ContentAutomationCheck> registros = ArgumentCaptor.forClass(ContentAutomationCheck.class);
		verify(checks).save(registros.capture());
		// Se o processo morrer no meio, o caso já está com uma pessoa — não com o bot.
		assertThat(registros.getValue().status()).isEqualTo(ReviewStatus.PENDING);
	}

	@Test
	void conteudoSeguroVoltaAoFeed() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		when(quarantinePost.release(anyString(), eq(POST_ID))).thenReturn(true);
		giveVerdict("NENHUMA", 0.9, false);

		var outcome = service.handle(POST_ID, "denuncia sem fundamento");

		assertThat(outcome.status()).isEqualTo("RELEASED");
		assertThat(outcome.released()).isTrue();
		verify(quarantinePost).release(SexualContentReportService.ACTOR, POST_ID);
		verify(checks).updateStatus(POST_ID, ReviewStatus.RELEASED);
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
	}

	@Test
	void duvidaDoBotFicaComUmaPessoa() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		giveVerdict("HUMAN_REVIEW", 0.55, true);

		var outcome = service.handle(POST_ID, "nao sei");

		assertThat(outcome.status()).isEqualTo("HUMAN_REVIEW");
		assertThat(outcome.quarantined()).isTrue();
		// Continua escondido e na fila: nem apaga nem devolve sem certeza.
		verify(quarantinePost, never()).release(anyString(), anyString());
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
		verify(checks, never()).updateStatus(anyString(), any());
	}

	@Test
	void penalizarAmbiguoNaoApaga() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		// Confiança alta, mas com ambiguidade declarada: o gate não deixa a automação apagar.
		giveVerdict("PENALIZAR", 0.95, true);

		var outcome = service.handle(POST_ID, "talvez seja explicito");

		assertThat(outcome.status()).isEqualTo("HUMAN_REVIEW");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
	}

	@Test
	void penalizarComConfiancaBaixaNaoApaga() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		giveVerdict("PENALIZAR", 0.5, false);

		var outcome = service.handle(POST_ID, "parece explicito");

		assertThat(outcome.status()).isEqualTo("HUMAN_REVIEW");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
	}

	@Test
	void semModeloOPostFicaEscondidoEFicaParaHumano() {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		when(ai.available()).thenReturn(false);

		var outcome = service.handle(POST_ID, "foto explicita");

		assertThat(outcome.status()).isEqualTo("HUMAN_REVIEW");
		assertThat(outcome.quarantined()).isTrue();
		verifyNoInteractions(removePost);
		verify(ai, never()).triage(anyString(), anyString(), anyString(), anyList());
	}

	@Test
	void vereditoInvalidoNaoApagaNemDevolve() throws Exception {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		when(ai.available()).thenReturn(true);
		when(ai.triage(anyString(), eq("SEXUAL_CONTENT"), anyString(), anyList()))
			.thenReturn(Optional.of("nao e json"));
		when(ai.parseVerdict("nao e json")).thenReturn(Optional.empty());

		var outcome = service.handle(POST_ID, "foto explicita");

		assertThat(outcome.status()).isEqualTo("HUMAN_REVIEW");
		verify(removePost, never()).removeAsModerator(anyString(), anyString(), anyString());
		verify(quarantinePost, never()).release(anyString(), anyString());
	}

	@Test
	void falhaDoModeloNaoDerrubaAPrecacao() {
		givePost();
		when(quarantinePost.quarantine(anyString(), eq(POST_ID), anyString())).thenReturn(true);
		when(ai.available()).thenReturn(true);
		when(mediaReader.imagesOf("POST", POST_ID, 3)).thenReturn(List.of());
		when(ai.triage(anyString(), eq("SEXUAL_CONTENT"), anyString(), anyList()))
			.thenThrow(new IllegalStateException("timeout"));

		var outcome = service.handle(POST_ID, "foto explicita");

		assertThat(outcome.status()).isEqualTo("HUMAN_REVIEW");
		assertThat(outcome.quarantined()).isTrue();
	}

	@Test
	void denunciaRepetidaNaoAvaliaDeNovo() {
		when(checks.findByPost(POST_ID)).thenReturn(Optional.of(ContentAutomationCheck.of(POST_ID, USER,
			com.socialcoisa.rede.moderation.domain.ContentAutomationVerdict.UNSURE, 0, "ja avaliado", "modelo",
			NOW, ReviewStatus.PENDING)));

		var outcome = service.handle(POST_ID, "foto explicita");

		assertThat(outcome.status()).isEqualTo("ALREADY_CHECKED");
		assertThat(outcome.quarantined()).isTrue();
		verifyNoInteractions(posts, quarantinePost, removePost, ai);
	}

	@Test
	void postForaDoArNaoTemOQueEsconder() {
		when(posts.findById(new PostId(POST_ID))).thenReturn(Optional.empty());

		var outcome = service.handle(POST_ID, "foto explicita");

		assertThat(outcome.status()).isEqualTo("SKIP");
		verifyNoInteractions(quarantinePost, removePost, ai);
	}

	@Test
	void desligadoNaoAge() {
		service = new SexualContentReportService(checks, posts, quarantinePost, removePost, mediaReader, ai,
			notifications, Clock.fixed(NOW, ZoneOffset.UTC), false);

		var outcome = service.handle(POST_ID, "foto explicita");

		assertThat(outcome.status()).isEqualTo("DISABLED");
		verifyNoInteractions(posts, quarantinePost, removePost, ai, notifications);
	}

	private void givePost() {
		Post post = Post.publish(new PostId(POST_ID), new UserId(USER), PostType.INSIGHT,
			"olha isso", null, List.of(), List.of("media_1"), null, NOW);
		when(posts.findById(new PostId(POST_ID))).thenReturn(Optional.of(post));
	}

	private void giveVerdict(String acao, double confianca, boolean ambiguo) throws Exception {
		JsonNode veredito = objectMapper.readTree(("{\"acao\":\"%s\",\"ambiguo\":%s,\"confianca\":%s,"
			+ "\"categoria\":\"SEXUAL\",\"justificativa\":\"conteudo explicito\"}")
			.formatted(acao, ambiguo, confianca));
		when(ai.available()).thenReturn(true);
		when(mediaReader.imagesOf("POST", POST_ID, 3)).thenReturn(List.of(new byte[] { 1, 2, 3 }));
		when(ai.triage(anyString(), eq("SEXUAL_CONTENT"), anyString(), anyList()))
			.thenReturn(Optional.of(veredito.toString()));
		when(ai.parseVerdict(anyString())).thenReturn(Optional.of(veredito));
	}
}
