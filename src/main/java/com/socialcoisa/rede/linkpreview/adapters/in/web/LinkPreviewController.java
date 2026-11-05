package com.socialcoisa.rede.linkpreview.adapters.in.web;

import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.linkpreview.application.LinkPreviewService;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

/**
 * A miniatura de um link publicado num post ou comentario.
 *
 * <p>Leitura PUBLICA: o post e publico e o cartao aparece para quem abre o link compartilhado, sem
 * sessao. A sessao, quando existe, so muda o que a pagina interna mostra (post de canal privado
 * continua invisivel para quem nao e membro).</p>
 *
 * <p>Resposta 200 com {@code preview: null} quando o endereco nao tem nada para mostrar — o front
 * desenha o link sem cartao. Nao e erro: site que nao responde e um resultado normal.</p>
 */
@RestController
class LinkPreviewController {

	private final LinkPreviewService previews;
	private final CurrentUserResolver currentUserResolver;

	LinkPreviewController(LinkPreviewService previews, CurrentUserResolver currentUserResolver) {
		this.previews = previews;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/link-preview")
	@RateLimiter(name = "linkPreview")
	LinkPreviewResponse preview(@RequestParam String url,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		Optional<LinkPreview> preview = previews.previewOf(url, viewerId(sessionToken));
		return new LinkPreviewResponse(preview.map(LinkPreviewView::from).orElse(null));
	}

	/** Sessao opcional: sem ela a leitura e anonima, como a do post. */
	private String viewerId(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id().value();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	record LinkPreviewResponse(LinkPreviewView preview) {
	}

	record LinkPreviewView(String kind, String url, String title, String description, String imageUrl,
			String siteName) {

		static LinkPreviewView from(LinkPreview preview) {
			return new LinkPreviewView(preview.kind().name(), preview.url(), preview.title(), preview.description(),
				preview.imageUrl(), preview.siteName());
		}
	}
}
