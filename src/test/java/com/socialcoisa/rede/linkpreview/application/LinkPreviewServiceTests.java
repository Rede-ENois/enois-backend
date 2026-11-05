package com.socialcoisa.rede.linkpreview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.linkpreview.application.port.out.LinkPreviewReader;
import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;

/**
 * A escolha entre pagina da casa e site de fora.
 *
 * <p>O ponto que importa: endereco do proprio Énóis NUNCA sai para a internet — o resumo vem do
 * banco. E um site de fora com o nosso dominio no nome (`enois.ia.br.algo.com`) tambem nao e nosso.
 */
class LinkPreviewServiceTests {

	private final InternalPageResolver internal = mock(InternalPageResolver.class);

	private final LinkPreviewReader external = mock(LinkPreviewReader.class);

	private final LinkPreviewService service = new LinkPreviewService(internal, external, "https://enois.ia.br");

	@Test
	void enderecoDoProprioSiteNaoSaiParaAInternet() {
		when(internal.resolve("/perfil/mel", null))
			.thenReturn(Optional.of(LinkPreview.internal("/perfil/mel", "Mel", null, null, "Perfil")));

		Optional<LinkPreview> preview = service.previewOf("https://enois.ia.br/perfil/mel", null);

		assertThat(preview).isPresent();
		assertThat(preview.get().kind()).isEqualTo(LinkPreview.Kind.INTERNAL);
		verify(external, never()).read(anyString());
	}

	@Test
	void caminhoRelativoTambemEOProprioSite() {
		when(internal.resolve("/post/abc", "user_1"))
			.thenReturn(Optional.of(LinkPreview.internal("/post/abc", "Post", null, null, "Post")));

		assertThat(service.previewOf("/post/abc", "user_1")).isPresent();
		verify(external, never()).read(anyString());
	}

	@Test
	void siteDeForaComONossoDominioNoNomeNaoENosso() {
		when(external.read("https://enois.ia.br.evil.com/post/x"))
			.thenReturn(Optional.of(new ExternalLinkPreview("https://enois.ia.br.evil.com/post/x", "Isca", null, null,
				null)));

		Optional<LinkPreview> preview = service.previewOf("https://enois.ia.br.evil.com/post/x", null);

		assertThat(preview).isPresent();
		assertThat(preview.get().kind()).isEqualTo(LinkPreview.Kind.EXTERNAL);
		verify(internal, never()).resolve(anyString(), any());
	}

	@Test
	void paginaInternaQueNaoExisteNaoViraLeituraExterna() {
		when(internal.resolve("/post/sumiu", null)).thenReturn(Optional.empty());

		assertThat(service.previewOf("https://enois.ia.br/post/sumiu", null)).isEmpty();
		verify(external, never()).read(anyString());
	}

	@Test
	void siteDeForaViraCartaoExterno() {
		when(external.read("https://jornal.com/artigo"))
			.thenReturn(Optional.of(new ExternalLinkPreview("https://jornal.com/artigo", "Titulo", "Descricao",
				"https://jornal.com/capa.png", "Jornal")));

		Optional<LinkPreview> preview = service.previewOf("https://jornal.com/artigo", null);

		assertThat(preview).isPresent();
		assertThat(preview.get().kind()).isEqualTo(LinkPreview.Kind.EXTERNAL);
		assertThat(preview.get().imageUrl()).isEqualTo("https://jornal.com/capa.png");
	}

	@Test
	void siteQueNaoRespondeNaoTemCartao() {
		when(external.read(anyString())).thenReturn(Optional.empty());

		assertThat(service.previewOf("https://fora-do-ar.com/x", null)).isEmpty();
	}

	@Test
	void enderecoVazioNaoConsultaNada() {
		assertThat(service.previewOf("", null)).isEmpty();
		assertThat(service.previewOf("   ", null)).isEmpty();
		verify(external, never()).read(anyString());
		verify(internal, never()).resolve(anyString(), any());
	}

	@Test
	void wwwEOmesmoSiteQueSemWww() {
		when(internal.resolve("/blogs", null))
			.thenReturn(Optional.of(LinkPreview.internal("/blogs", "Blogs", null, null, "Blog")));

		assertThat(service.previewOf("https://www.enois.ia.br/blogs", null)).isPresent();
		verify(external, never()).read(anyString());
	}

	@Test
	void dominioParecidoNaoConfundeOPrefixo() {
		// `enois.ia.br.coisa.com` e outro host; `myenois.ia.br` tambem.
		assertThat(service.internalPath("https://enois.ia.br.coisa.com/x")).isNull();
		assertThat(service.internalPath("https://myenois.ia.br/x")).isNull();
		assertThat(service.internalPath("https://enois.ia.br/x")).isEqualTo("/x");
		assertThat(service.internalPath("/x")).isEqualTo("/x");
	}

	@Test
	void queryStringNaoFazParteDoCaminhoInterno() {
		when(internal.resolve("/vagas/abc", null))
			.thenReturn(Optional.of(LinkPreview.internal("/vagas/abc", "Vaga", null, null, "Vaga")));

		assertThat(service.previewOf("https://enois.ia.br/vagas/abc?utm_source=x", null)).isPresent();
	}
}
