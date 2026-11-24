package com.socialcoisa.rede.moderation.adapters.out.media;

import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.socialcoisa.rede.colagem.application.port.out.ColagemRepository;
import com.socialcoisa.rede.colagem.domain.ColagemComment;
import com.socialcoisa.rede.colagem.domain.ColagemPost;
import com.socialcoisa.rede.colagem.domain.ColagemStatus;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaSourceDownloader;
import com.socialcoisa.rede.moderation.application.EvidenciaSancaoService;
import com.socialcoisa.rede.moderation.application.port.out.BanContentArchiveRepository;
import com.socialcoisa.rede.moderation.application.port.out.CallReportContextRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.moderation.domain.UserSanction;

import tools.jackson.databind.ObjectMapper;

@Tag("critico")
class MetadadosMidiaColagemTests {

	private static final Instant AGORA = Instant.parse("2026-11-16T12:00:00Z");
	private final ColagemRepository colagem = mock(ColagemRepository.class);
	private final PostRepository posts = mock(PostRepository.class);
	private final CommentRepository comentarios = mock(CommentRepository.class);
	private final MediaAssetRepository assets = mock(MediaAssetRepository.class);
	private final MediaSourceDownloader origem = mock(MediaSourceDownloader.class);
	private final CallReportContextRepository chamadas = mock(CallReportContextRepository.class);
	private final ObjectProvider<MediaSourceDownloader> downloader = new StaticListableBeanFactory(
		Map.of("origem", origem)).getBeanProvider(MediaSourceDownloader.class);
	private final ReportedMediaAdapter metadados = new ReportedMediaAdapter(
		posts, comentarios, assets, downloader, chamadas, colagem);

	@ParameterizedTest
	@MethodSource("associacoesPersistidas")
	void reconheceSomenteAssociacaoDeMidiaInclusiveRemovida(TargetType tipo, ColagemStatus estado, String midia) {
		prepararAlvo(tipo, estado, midia);
		assertThat(metadados.hasMediaOf(denuncia(tipo))).isEqualTo(midia != null && !midia.isBlank());
		verificarConsultaColagem(tipo);
		verifyNoInteractions(assets, origem, posts, comentarios, chamadas);
	}

	@ParameterizedTest
	@EnumSource(value = TargetType.class, names = {"COLLAGEM_POST", "COLLAGEM_COMMENT"})
	void alvoAusenteNaoTemAssociacaoDeMidia(TargetType tipo) {
		assertThat(metadados.hasMediaOf(denuncia(tipo))).isFalse();
		verificarConsultaColagem(tipo);
		verifyNoInteractions(assets, origem, posts, comentarios, chamadas);
	}

	@ParameterizedTest
	@MethodSource("alvosEEstados")
	void evidenciaFotograficaNaoUsaMarcadorDeTriagemComoTexto(TargetType tipo, ColagemStatus estado) {
		prepararAlvo(tipo, estado, "midia-sintetica");
		var denuncia = denuncia(tipo);
		var denuncias = mock(ReportRepository.class);
		var autores = mock(ReportedContentAuthorReader.class);
		var conteudo = mock(ReportedContentReader.class);
		var arquivo = mock(BanContentArchiveRepository.class);
		when(denuncias.findById(denuncia.id())).thenReturn(Optional.of(denuncia));
		when(autores.authorOf(denuncia)).thenReturn(Optional.of("titular-sintetico"));
		String marcador = tipo == TargetType.COLLAGEM_POST
			? "[foto da Colagem sem legenda]" : "[comentário fotográfico na Colagem]";
		when(conteudo.contentOf(tipo.name(), denuncia.targetId())).thenReturn(Optional.of(marcador));
		var servico = new EvidenciaSancaoService(denuncias, conteudo, autores, arquivo, new ObjectMapper(), metadados);
		var evidencia = servico.daSancao(UserSanction.suspended("sancao-sintetica", "titular-sintetico",
			denuncia.id(), "Decisao sintetica", AGORA));
		assertThat(evidencia.kind()).isEqualTo("MEDIA");
		assertThat(evidencia.text()).doesNotContain(marcador);
		verifyNoInteractions(conteudo, arquivo, assets, origem);
	}

	@ParameterizedTest
	@EnumSource(value = TargetType.class, names = {"COLLAGEM_POST", "COLLAGEM_COMMENT"})
	void leituraDeImagensParaTriagemPermaneceSemSuporteAColagem(TargetType tipo) {
		assertThat(metadados.imagesOf(denuncia(tipo), 1)).isEmpty();
		assertThat(metadados.imagesOf(tipo.name(), "alvo-sintetico", 1)).isEmpty();
		verifyNoInteractions(colagem, assets, origem, posts, comentarios, chamadas);
	}

	private void prepararAlvo(TargetType tipo, ColagemStatus estado, String midia) {
		var autor = new UserId("titular-sintetico");
		if (tipo == TargetType.COLLAGEM_POST) {
			when(colagem.findPostById("alvo-sintetico")).thenReturn(Optional.of(new ColagemPost(
				"alvo-sintetico", autor, midia, null, false, AGORA, estado,
				estado == ColagemStatus.REMOVED ? AGORA : null)));
			return;
		}
		when(colagem.findCommentById("alvo-sintetico")).thenReturn(Optional.of(new ColagemComment(
			"alvo-sintetico", "post-sintetico", autor, null, null, midia, false, AGORA, estado)));
	}

	private void verificarConsultaColagem(TargetType tipo) {
		if (tipo == TargetType.COLLAGEM_POST) verify(colagem).findPostById("alvo-sintetico");
		else verify(colagem).findCommentById("alvo-sintetico");
		verifyNoMoreInteractions(colagem);
	}

	private Report denuncia(TargetType tipo) {
		return Report.create("denuncia-sintetica", "relator-sintetico", tipo, "alvo-sintetico",
			ReportReason.OTHER, "Relato sintetico", AGORA);
	}

	private static Stream<Arguments> alvosEEstados() {
		return Stream.of(TargetType.COLLAGEM_POST, TargetType.COLLAGEM_COMMENT)
			.flatMap(tipo -> Stream.of(ColagemStatus.ACTIVE, ColagemStatus.REMOVED)
				.map(estado -> Arguments.of(tipo, estado)));
	}

	private static Stream<Arguments> associacoesPersistidas() {
		return Stream.of(TargetType.COLLAGEM_POST, TargetType.COLLAGEM_COMMENT)
			.flatMap(tipo -> Stream.of(ColagemStatus.ACTIVE, ColagemStatus.REMOVED)
				.flatMap(estado -> Stream.of(null, "", "   ", "midia-sintetica")
					.map(midia -> Arguments.of(tipo, estado, midia))));
	}
}
