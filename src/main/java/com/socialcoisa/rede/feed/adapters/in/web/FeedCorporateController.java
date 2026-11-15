package com.socialcoisa.rede.feed.adapters.in.web;

import java.time.Instant;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.feed.application.GetFeedService;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Rotas do feed corporativo e da página do perfil profissional.
 *
 * A separação vive no backend (ver GetFeedService): estas rotas só devolvem o que foi assinado
 * pela identidade profissional (BUSINESS), e o feed social jamais devolve isso.
 */
@RestController
class FeedCorporateController {

	private final GetFeedService getFeedService;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	FeedCorporateController(GetFeedService getFeedService, CurrentUserResolver currentUserResolver,
			CursorCodec cursorCodec) {
		this.getFeedService = getFeedService;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/feed/corporate")
	Object corporateFeed(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		// Público, na mesma regra do perfil profissional: a página de um perfil corporativo é
		// pública, então o feed agregado do mesmo conteúdo não pode exigir mais que ela. Com
		// sessão válida o visitante é alguém (personalização e bloqueios valem); sem, é ninguém.
		return getFeedService.getCorporateFeed(viewerIdOrNull(sessionToken),
			cursorCodec.decode(cursor).orElse(null), limit);
	}

	@GetMapping("/corporate/{profileUsername}/posts")
	Object corporateProfilePosts(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String profileUsername,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		// Leitura pública como a do perfil social: sem sessão válida o visitante é NINGUÉM.
		return getFeedService.getCorporateProfilePosts(profileUsername, cursorCodec.decode(cursor).orElse(null), limit,
			viewerIdOrNull(sessionToken));
	}

	private UserId viewerIdOrNull(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id();
		}
		catch (Exception ignored) {
			return null;
		}
	}
}
