package com.socialcoisa.rede.calendar.adapters.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calendar.application.CalendarSubscriptionService;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.calendar.domain.CalendarSubscription;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Gestão da assinatura privada `.ics` (fase 9, D7) — rotas COM sessão.
 *
 * <p>Criar devolve o token em claro uma única vez; depois disso ele não é recuperável. Por isso
 * `GET` responde só se existe assinatura, com o recorte e a data, e nunca o link. Regenerar é
 * `POST /subscription/regenerate`: o link anterior morre na mesma transação, então não existe
 * janela com dois links vivos.
 *
 * <p>A rota de leitura sem sessão fica em {@link CalendarSubscriptionController}: aqui a sessão é
 * obrigatória e é ela que decide de quem é a agenda.
 */
@RestController
@RequestMapping("/calendar/subscription")
class CalendarSubscriptionSettingsController {

	private final CurrentUserResolver currentUserResolver;
	private final CalendarSubscriptionService subscriptions;

	CalendarSubscriptionSettingsController(CurrentUserResolver currentUserResolver,
			CalendarSubscriptionService subscriptions) {
		this.currentUserResolver = currentUserResolver;
		this.subscriptions = subscriptions;
	}

	/** Existe assinatura ativa? Sem token, que já não é recuperável. */
	@GetMapping
	ResponseEntity<CalendarSubscriptionView> view(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return subscriptions.active(user.id().value())
			.map(subscription -> ResponseEntity.ok(CalendarSubscriptionView.of(subscription, null)))
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/**
	 * Cria a assinatura, ou devolve a existente. Só a criação traz o token: quem já tinha uma
	 * recebe `token: null` e a tela mostra que o link antigo continua valendo.
	 */
	@PostMapping
	ResponseEntity<CalendarSubscriptionView> issue(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String scope) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		CalendarSubscriptionService.Issued issued = subscriptions.issue(user.id().value(), scope(scope));
		return ResponseEntity.ok(CalendarSubscriptionView.of(issued.subscription(), issued.token()));
	}

	/** Token novo na mesma assinatura; o link antigo deixa de valer agora. */
	@PostMapping("/regenerate")
	ResponseEntity<CalendarSubscriptionView> regenerate(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		CalendarSubscriptionService.Issued issued = subscriptions.regenerate(user.id().value());
		return ResponseEntity.ok(CalendarSubscriptionView.of(issued.subscription(), issued.token()));
	}

	/** Revoga a ativa; a próxima leitura pelo link responde 404. */
	@DeleteMapping
	ResponseEntity<Void> revoke(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		subscriptions.revoke(user.id().value());
		return ResponseEntity.noContent().build();
	}

	private static CalendarScope scope(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return CalendarScope.parse(raw.trim());
		}
		catch (IllegalArgumentException unknown) {
			throw new ApiException(400, "INVALID_SCOPE", "Agenda inválida: use SOCIAL ou PROFESSIONAL.");
		}
	}

	/**
	 * O que a tela precisa para mostrar o estado do link. O token só aparece quando acabou de
	 * nascer: depois, a única saída é regenerar.
	 */
	record CalendarSubscriptionView(String scope, String createdAt, String revokedAt, String token) {

		static CalendarSubscriptionView of(CalendarSubscription subscription, String token) {
			return new CalendarSubscriptionView(subscription.scope() == null ? null : subscription.scope().name(),
				subscription.createdAt().toString(),
				subscription.revokedAt() == null ? null : subscription.revokedAt().toString(), token);
		}
	}
}