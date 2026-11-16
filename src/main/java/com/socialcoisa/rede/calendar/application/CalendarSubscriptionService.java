package com.socialcoisa.rede.calendar.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.calendar.application.port.out.CalendarSubscriptionRepository;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.calendar.domain.CalendarSubscription;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Gestão e leitura da assinatura privada `.ics` (fase 9, D7).
 *
 * <p>A pessoa tem no máximo uma assinatura ativa. Criar quando já existe responde a existente sem
 * gerar token novo — não há como mostrá-la de novo, e inventar um segundo link só multiplicaria
 * credenciais vivas. Regenerar troca o hash da mesma linha e mata o link antigo na hora; revogar
 * marca `revoked_at` e a leitura sem sessão passa a responder 404.
 *
 * <p>O token em claro só existe na resposta que o gerou. O banco tem o hash, então nem quem lê a
 * tabela consegue reconstruir o link.
 */
@Service
public class CalendarSubscriptionService {

	/**
	 * A janela da assinatura: o passado recente dá contexto ao leitor externo, e o total fica
	 * dentro do teto de um ano que o export aceita. O fim é derivado do começo de propósito —
	 * somar os dois prazos soltos estouraria o teto (30 + 365 dias).
	 */
	private static final Duration PAST = Duration.ofDays(30);
	private static final Duration TOTAL = Duration.ofDays(365);

	private final CalendarSubscriptionRepository subscriptions;
	private final CalendarIcsService ics;
	private final Clock clock;

	CalendarSubscriptionService(CalendarSubscriptionRepository subscriptions, CalendarIcsService ics,
			Clock clock) {
		this.subscriptions = subscriptions;
		this.ics = ics;
		this.clock = clock;
	}

	/** A assinatura ativa da pessoa, sem o token (que não é recuperável). */
	@Transactional(readOnly = true)
	public Optional<CalendarSubscription> active(String userId) {
		return subscriptions.findActiveByUser(userId);
	}

	/**
	 * A assinatura ativa ou uma nova. Devolve o token em claro, que só existe aqui — quem já tinha
	 * uma recebe {@code null} e a tela mostra que o link antigo continua valendo.
	 *
	 * <p>A emissão é o único caminho que cria linha, e ela é serializada pela tranca da pessoa:
	 * duas emissões correndo juntas não nascem com dois tokens ativos.
	 *
	 * <p>Pedir um recorte diferente do que a assinatura ativa tem é recusado: devolver a existente
	 * em silêncio faria a pessoa achar que acabou de assinar a agenda profissional e receber um
	 * arquivo com a social. O recorte é congelado no token; trocá-lo é regenerar o link (D7).
	 */
	@Transactional
	public Issued issue(String userId, CalendarScope scope) {
		// Duas emissões correndo juntas leriam o mesmo "não há ativa" e criariam dois links vivos —
		// e o revogar só mata um, deixando credencial ativa que a pessoa não conhece. A tranca na
		// linha da pessoa serializa a checagem e a criação: quem chega depois já vê a ativa.
		subscriptions.lockUserForIssue(userId);
		Optional<CalendarSubscription> existing = subscriptions.findActiveByUser(userId);
		if (existing.isPresent()) {
			CalendarSubscription current = existing.get();
			if (scope != null && scope != current.scope()) {
				throw new ApiException(409, "CALENDAR_SUBSCRIPTION_SCOPE_CONFLICT",
					"A assinatura ativa cobre outra agenda; regenere o link para trocar o recorte.");
			}
			return new Issued(current, null);
		}
		String token = CalendarSubscription.newToken();
		CalendarSubscription created = CalendarSubscription.create(UUID.randomUUID().toString(), userId, scope,
			Hashes.sha256Hex(token), clock.instant());
		subscriptions.save(created);
		return new Issued(created, token);
	}

	/**
	 * Troca o token da assinatura ativa. O link anterior deixa de valer no mesmo instante; nenhuma
	 * linha nova nasce, então não existe janela com dois links vivos.
	 */
	@Transactional
	public Issued regenerate(String userId) {
		CalendarSubscription current = subscriptions.findActiveByUser(userId)
			.orElseThrow(() -> new ApiException(404, "CALENDAR_SUBSCRIPTION_NOT_FOUND",
				"Não há assinatura ativa para regenerar."));
		String token = CalendarSubscription.newToken();
		CalendarSubscription rotated = new CalendarSubscription(current.id(), current.userId(), current.scope(),
			Hashes.sha256Hex(token), current.createdAt(), null);
		subscriptions.save(rotated);
		return new Issued(rotated, token);
	}

	/** Revoga a ativa. Sem assinatura ativa é 404: não há o que revogar. */
	@Transactional
	public void revoke(String userId) {
		CalendarSubscription current = subscriptions.findActiveByUser(userId)
			.orElseThrow(() -> new ApiException(404, "CALENDAR_SUBSCRIPTION_NOT_FOUND",
				"Não há assinatura ativa para revogar."));
		current.revoke(clock.instant());
		subscriptions.save(current);
	}

	/**
	 * O `.ics` de quem apresenta o token, sem sessão. Token desconhecido e token revogado respondem
	 * o mesmo 404: distinguir confirmaria que aquele link já existiu.
	 */
	@Transactional(readOnly = true)
	public SubscribedCalendar read(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw notFound();
		}
		CalendarSubscription subscription = subscriptions.findByTokenHash(Hashes.sha256Hex(rawToken))
			.filter(CalendarSubscription::active)
			.orElseThrow(CalendarSubscriptionService::notFound);
		Instant from = clock.instant().minus(PAST);
		Instant to = from.plus(TOTAL);
		String body = ics.export(subscription.userId(), from, to, subscription.scope());
		return new SubscribedCalendar(subscription, body, from, to);
	}

	private static ApiException notFound() {
		return new ApiException(404, "CALENDAR_SUBSCRIPTION_NOT_FOUND",
			"Assinatura inválida ou revogada.");
	}

	/** O token em claro só existe quando nasceu ({@code null} quando a assinatura já existia). */
	public record Issued(CalendarSubscription subscription, String token) {
	}

	/** O arquivo e a janela que ele cobre — a rota usa a janela para o `ETag` e o `Cache-Control`. */
	public record SubscribedCalendar(CalendarSubscription subscription, String body, Instant from, Instant to) {
	}
}