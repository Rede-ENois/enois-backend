package com.socialcoisa.rede.feed.adapters.in.web;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.feed.application.GetPageDocumentUseCase;
import com.socialcoisa.rede.feed.application.PagePreviewCardService;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.SiteDomains;

/**
 * Documento HTML de uma pagina publicada — a origem de conteudo.
 *
 * Por que existe: player de terceiros (YouTube e companhia) so toca se o documento que o embute
 * tiver uma ORIGEM de verdade, e a pagina precisa poder rodar dentro de um quadro com scripts
 * liberados (sem isso o player nao monta). Servir esse documento na mesma origem do app seria
 * entregar a sessao de quem le para o HTML colado por outra pessoa. Aqui ele nasce numa origem
 * separada — por isso a pagina pode ter `allow-scripts` sem que isso signifique acesso ao app.
 *
 * E a politica vai no CABECALHO, nao num `<meta>` dentro do documento: cabecalho nao pode ser
 * alterado pelo conteudo, nem removido por quem colou, nem sobrescrito por HTML injetado.
 *
 * Os quatro freios, em camadas:
 *  1. o HTML guardado ja passou pelo sanitizador na gravacao (`SafeHtml`);
 *  2. a CSP abaixo proibe script NA PAGINA (o player de terceiro roda no documento dele, que tem
 *     politica propria — por isso `frame-src` lista os provedores e `script-src` fica `'none'`);
 *  3. `frame-ancestors` limita quem pode embutir a pagina (so o app);
 *  4. o quadro no front tem sandbox, e a origem do documento e separada da origem do app.
 */
@RestController
class PageContentController {

	private final GetPageDocumentUseCase getPageDocumentUseCase;
	private final PagePreviewCardService pagePreviewCardService;
	private final UserProfileRepository userProfileRepository;
	private final String contentSecurityPolicy;

	PageContentController(GetPageDocumentUseCase getPageDocumentUseCase,
			PagePreviewCardService pagePreviewCardService,
			UserProfileRepository userProfileRepository,
			SiteDomains siteDomains,
			@Value("${rede.pages.content-security-policy:}") String contentSecurityPolicy) {
		this.getPageDocumentUseCase = getPageDocumentUseCase;
		this.pagePreviewCardService = pagePreviewCardService;
		this.userProfileRepository = userProfileRepository;
		this.contentSecurityPolicy = StringUtils.hasText(contentSecurityPolicy) ? contentSecurityPolicy
			: defaultCsp(siteDomains);
	}

	@GetMapping(value = "/pages/{postId}", produces = MediaType.TEXT_HTML_VALUE)
	ResponseEntity<byte[]> page(@PathVariable String postId) {
		String html = getPageDocumentUseCase.getPageDocument(new PostId(postId)).html();

		return ResponseEntity.ok()
			.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
			.header("Content-Security-Policy", contentSecurityPolicy)
			// Sem `nosniff` o navegador pode adivinhar o tipo do conteudo e executar o que nao devia.
			.header("X-Content-Type-Options", "nosniff")
			// Nada sai daqui para terceiros por cabecalho.
			.header("Referrer-Policy", "no-referrer")
			.cacheControl(CacheControl.noCache())
			.body(html.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Cartao que aparece na miniatura do link de uma pagina: as primeiras linhas do que a pessoa
	 * escreveu, desenhadas a partir do texto da pagina. Imagem de marca nao serve — quem publica uma
	 * pagina quer ver o que escreveu.
	 */
	@GetMapping(value = "/pages/{postId}/og.png", produces = MediaType.IMAGE_PNG_VALUE)
	ResponseEntity<byte[]> previewCard(@PathVariable String postId) {
		var document = getPageDocumentUseCase.getPageDocument(new PostId(postId));
		String author = userProfileRepository.findById(document.authorId())
			.map(profile -> profile.name())
			.orElse(null);

		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_PNG)
			// Um dia no navegador/plataforma; o servidor guarda em cache proprio.
			.header("Cache-Control", "public, max-age=86400")
			.body(pagePreviewCardService.render(document.postId(), document.html(), author));
	}

	/**
	 * Politica padrao. `frame-src` lista so provedores conhecidos (player), `script-src` fica
	 * `'none'` para o documento da pagina e `frame-ancestors` libera apenas o app, em cada dominio
	 * do site (principal e espelhos, `rede.site.domains`) — a politica inteira e ajustavel pela
	 * propriedade `rede.pages.content-security-policy`, porque a origem muda por ambiente.
	 */
	static String defaultCsp(SiteDomains siteDomains) {
		String ancestors = siteDomains.all().stream().map(d -> "https://" + d + " https://*." + d)
			.collect(Collectors.joining(" "));
		return BASE_CSP + "frame-ancestors 'self' " + ancestors;
	}

	private static final String BASE_CSP = "default-src 'none'; "
		+ "img-src https: data:; media-src https: data:; font-src data:; style-src 'unsafe-inline'; "
		+ "frame-src https://www.youtube-nocookie.com https://www.youtube.com https://player.vimeo.com "
		+ "https://www.dailymotion.com https://player.twitch.tv https://open.spotify.com "
		+ "https://w.soundcloud.com; "
		+ "script-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'; ";
}
