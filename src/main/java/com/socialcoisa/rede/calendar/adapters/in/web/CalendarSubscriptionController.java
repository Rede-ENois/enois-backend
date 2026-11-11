package com.socialcoisa.rede.calendar.adapters.in.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calendar.application.CalendarSubscriptionService;

/**
 * Leitura da agenda por assinatura privada, sem sessão (fase 9, D7).
 *
 * <p>O token no caminho é a única credencial: não há cookie, não há `X-Session-Token`, e a rota
 * responde o mesmo `text/calendar` do export autenticado. O que muda é a janela — quem assinou não
 * manda `from`/`to`, então o servidor entrega uma janela fixa (30 dias atrás, 365 à frente) e o
 * `ETag` do corpo deixa o leitor externo economizar o download quando nada mudou.
 *
 * <p>O recorte é o que ficou congelado no token na criação: assinar a agenda consolidada não deve
 * virar assinar uma só por causa de um parâmetro na URL.
 */
@RestController
@RequestMapping("/calendar/subscribe")
class CalendarSubscriptionController {

	/** Curto: a agenda muda durante o dia, e um leitor externo revalida barato pelo `ETag`. */
	private static final CacheControl CACHE = CacheControl.maxAge(java.time.Duration.ofMinutes(5)).cachePrivate();

	private final CalendarSubscriptionService subscriptions;

	CalendarSubscriptionController(CalendarSubscriptionService subscriptions) {
		this.subscriptions = subscriptions;
	}

	@GetMapping(value = "/{token}.ics", produces = "text/calendar;charset=UTF-8")
	ResponseEntity<byte[]> calendar(@PathVariable("token") String token) {
		CalendarSubscriptionService.SubscribedCalendar calendar = subscriptions.read(token);
		byte[] body = calendar.body().getBytes(StandardCharsets.UTF_8);
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"agenda-enois.ics\"")
			.eTag(etag(body))
			.cacheControl(CACHE)
			.contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
			.body(body);
	}

	/** Hash do corpo: o mesmo arquivo responde 304, e uma entrada nova muda o `ETag` na hora. */
	private static String etag(byte[] body) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
			return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 indisponível", exception);
		}
	}
}