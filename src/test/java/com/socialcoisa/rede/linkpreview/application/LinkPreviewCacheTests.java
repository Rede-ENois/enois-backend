package com.socialcoisa.rede.linkpreview.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.socialcoisa.rede.linkpreview.application.port.out.LinkPreviewReader;
import com.socialcoisa.rede.linkpreview.domain.ExternalLinkPreview;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;
import com.socialcoisa.rede.shared.application.CacheConfig;

/**
 * As chaves do cache da miniatura, provadas pela chamada ao leitor (a anotacao e a chamada, nao o
 * rotulo).
 *
 * <p>O que importa: (a) um site de fora e lido UMA vez para todo mundo, (b) a leitura anonima vem
 * antes e responde por todos quando mostra o cartao, (c) o "nao tem nada" NAO fica no cache, senao
 * cada visitante de um post privado pagaria a consulta de novo.</p>
 */
class LinkPreviewCacheTests {

	private InternalPageResolver internal;

	private LinkPreviewReader external;

	private AnnotationConfigApplicationContext context;

	private LinkPreviewCache cache;

	@Configuration(proxyBeanMethods = false)
	@EnableCaching(proxyTargetClass = true)
	static class TestCacheConfig {

		@Bean
		CacheManager cacheManager() {
			SimpleCacheManager manager = new SimpleCacheManager();
			manager.setCaches(List.of(new CaffeineCache(CacheConfig.LINK_PREVIEWS,
				Caffeine.newBuilder().expireAfterWrite(java.time.Duration.ofMinutes(5)).maximumSize(100).build())));
			return manager;
		}
	}

	@BeforeEach
	void setUp() {
		internal = mock(InternalPageResolver.class);
		external = mock(LinkPreviewReader.class);
		context = new AnnotationConfigApplicationContext();
		context.register(TestCacheConfig.class);
		context.registerBean(InternalPageResolver.class, () -> internal);
		context.registerBean(LinkPreviewReader.class, () -> external);
		context.registerBean(LinkPreviewCache.class);
		context.refresh();
		cache = context.getBean(LinkPreviewCache.class);
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void siteDeForaELidoUmaVezParaTodosOsLeitores() {
		when(external.read("https://jornal.com/artigo")).thenReturn(
			Optional.of(new ExternalLinkPreview("https://jornal.com/artigo", "Titulo", null, null, "Jornal")));

		assertThat(cache.external("https://jornal.com/artigo")).isNotNull();
		assertThat(cache.external("https://jornal.com/artigo")).isNotNull();

		verify(external, times(1)).read("https://jornal.com/artigo");
	}

	@Test
	void paginaInternaAnonimaECompartilhadaEntreVisitas() {
		LinkPreview cartao = LinkPreview.internal("/perfil/mel", "Mel", null, null, "Perfil");
		when(internal.resolve("/perfil/mel", null)).thenReturn(Optional.of(cartao));

		assertThat(cache.internalPublic("/perfil/mel")).isEqualTo(cartao);
		assertThat(cache.internalPublic("/perfil/mel")).isEqualTo(cartao);

		verify(internal, times(1)).resolve("/perfil/mel", null);
	}

	@Test
	void paginaInternaComSessaoTemChavePropriaPorLeitor() {
		doReturn(Optional.of(LinkPreview.internal("/post/p1", "Para o Mel", null, null, "Post")))
			.when(internal).resolve("/post/p1", "mel");
		doReturn(Optional.of(LinkPreview.internal("/post/p1", "Para a Ana", null, null, "Post")))
			.when(internal).resolve("/post/p1", "ana");

		assertThat(cache.internalForViewer("/post/p1", "mel").title()).isEqualTo("Para o Mel");
		assertThat(cache.internalForViewer("/post/p1", "ana").title()).isEqualTo("Para a Ana");
		assertThat(cache.internalForViewer("/post/p1", "mel").title()).isEqualTo("Para o Mel");

		verify(internal, times(1)).resolve("/post/p1", "mel");
		verify(internal, times(1)).resolve("/post/p1", "ana");
	}

	@Test
	void oNadaNaoFicaNoCache() {
		when(internal.resolve(anyString(), any())).thenReturn(Optional.empty());
		when(external.read(anyString())).thenReturn(Optional.empty());

		assertThat(cache.internalPublic("/post/privado")).isNull();
		assertThat(cache.internalPublic("/post/privado")).isNull();
		assertThat(cache.internalForViewer("/post/privado", "mel")).isNull();
		assertThat(cache.internalForViewer("/post/privado", "mel")).isNull();
		assertThat(cache.external("https://fora-do-ar.com/x")).isNull();
		assertThat(cache.external("https://fora-do-ar.com/x")).isNull();

		verify(internal, times(2)).resolve("/post/privado", null);
		verify(internal, times(2)).resolve("/post/privado", "mel");
		verify(external, times(2)).read("https://fora-do-ar.com/x");
	}

	@Test
	void leituraAnonimaVemAntesDaLeituraPessoal() {
		LinkPreview publica = LinkPreview.internal("/perfil/mel", "Mel", null, null, "Perfil");
		when(internal.resolve("/perfil/mel", null)).thenReturn(Optional.of(publica));
		LinkPreviewService service = new LinkPreviewService(context.getBean(LinkPreviewCache.class),
			"https://enois.ia.br");

		assertThat(service.previewOf("/perfil/mel", "mel")).contains(publica);

		// A pessoa logada recebeu o cartao ANONIMO: a leitura pessoal nem foi feita.
		verify(internal, never()).resolve("/perfil/mel", "mel");
	}

	@Test
	void leituraPessoalSoAconteceQuandoAAnonimaNaoAchou() {
		when(internal.resolve("/post/p1", null)).thenReturn(Optional.empty());
		when(internal.resolve("/post/p1", "mel"))
			.thenReturn(Optional.of(LinkPreview.internal("/post/p1", "Do canal", null, null, "Post")));
		LinkPreviewService service = new LinkPreviewService(context.getBean(LinkPreviewCache.class),
			"https://enois.ia.br");

		assertThat(service.previewOf("/post/p1", "mel").orElseThrow().title()).isEqualTo("Do canal");
		assertThat(service.previewOf("/post/p1", null)).isEmpty();
		verify(external, never()).read(anyString());
	}
}
