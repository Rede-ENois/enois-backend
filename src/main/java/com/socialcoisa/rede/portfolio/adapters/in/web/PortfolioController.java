package com.socialcoisa.rede.portfolio.adapters.in.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.portfolio.application.PortfolioService;
import com.socialcoisa.rede.portfolio.application.PortfolioService.BlockInput;
import com.socialcoisa.rede.portfolio.domain.PortfolioBlockType;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Portfólio profissional.
 *
 * <p>A leitura pública é por username (`GET /portfolio/{username}`) e devolve só a última
 * publicação. Tudo que o dono edita fica em `/me/portfolio`: o rascunho e as peças nunca aparecem
 * na leitura pública, nem depois de publicados, até a próxima publicação.</p>
 */
@RestController
class PortfolioController {

	private final PortfolioService service;
	private final CurrentUserResolver currentUserResolver;

	PortfolioController(PortfolioService service, CurrentUserResolver currentUserResolver) {
		this.service = service;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping("/portfolio/{username}")
	PortfolioViews.Published byUsername(@PathVariable String username) {
		return PortfolioViews.Published.from(service.byUsername(username));
	}

	@GetMapping("/me/portfolio")
	PortfolioViews.Mine mine(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return PortfolioViews.Mine.from(service.own(user.id().value()));
	}

	@PutMapping("/me/portfolio/draft")
	PortfolioViews.Mine saveDraft(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody DraftRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return PortfolioViews.Mine.from(service.saveDraft(user.id().value(), request.html()));
	}

	@PostMapping("/me/portfolio/blocks")
	ResponseEntity<PortfolioViews.Mine> createBlock(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody BlockRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return ResponseEntity.status(201).body(PortfolioViews.Mine.from(service.saveBlock(user.id().value(), null,
			request.toInput())));
	}

	@PutMapping("/me/portfolio/blocks/{blockId}")
	PortfolioViews.Mine updateBlock(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String blockId, @RequestBody BlockRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return PortfolioViews.Mine.from(service.saveBlock(user.id().value(), blockId, request.toInput()));
	}

	@DeleteMapping("/me/portfolio/blocks/{blockId}")
	PortfolioViews.Mine deleteBlock(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String blockId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return PortfolioViews.Mine.from(service.deleteBlock(user.id().value(), blockId));
	}

	@PutMapping("/me/portfolio/order")
	PortfolioViews.Mine reorder(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody OrderRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return PortfolioViews.Mine.from(service.reorder(user.id().value(), request.order()));
	}

	@PostMapping("/me/portfolio/publish")
	PortfolioViews.Published publish(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return PortfolioViews.Published.from(service.publish(user.id().value()));
	}

	record DraftRequest(String html) {
	}

	record OrderRequest(List<String> order) {
	}

	record BlockRequest(String type, String title, String text, String projectId, String mediaId, String linkUrl) {

		BlockInput toInput() {
			return new BlockInput(parse(type), title, text, projectId, mediaId, linkUrl);
		}

		private static PortfolioBlockType parse(String raw) {
			if (raw == null || raw.isBlank()) {
				return null;
			}
			try {
				return PortfolioBlockType.valueOf(raw.trim().toUpperCase());
			}
			catch (IllegalArgumentException desconhecido) {
				throw new ApiException(400, "PORTFOLIO_BLOCK_TYPE_REQUIRED", "Tipo de peca desconhecido.");
			}
		}
	}
}
