package com.socialcoisa.rede.shared.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CursorCodec {

	public Optional<Instant> decode(String cursor) {
		return decodePage(cursor).map(PageCursor::instant);
	}

	public String encode(Instant instant) {
		return encodePage(instant, null);
	}

	/**
	 * Cursor com desempate: o instante pode se repetir (a coluna guarda segundos), e paginar so pelo
	 * instante estrito pula as linhas que empataram com ele. O segundo valor identifica a ultima
	 * linha entregue dentro daquele instante.
	 *
	 * <p>O par vai em base64url para nao depender de escape na query string. Cursor no formato
	 * antigo (instante puro) continua valido, com desempate nulo.</p>
	 */
	public Optional<PageCursor> decodePage(String cursor) {
		if (!StringUtils.hasText(cursor)) {
			return Optional.empty();
		}
		try {
			return Optional.of(new PageCursor(Instant.parse(cursor), null));
		}
		catch (RuntimeException notAnInstant) {
			// Formato composto; se tambem nao decodificar, o cursor e invalido.
			try {
				String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
				int separator = decoded.indexOf('|');
				if (separator <= 0) {
					throw new IllegalArgumentException("cursor sem desempate");
				}
				return Optional.of(new PageCursor(Instant.parse(decoded.substring(0, separator)),
					decoded.substring(separator + 1)));
			}
			catch (RuntimeException invalid) {
				throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
			}
		}
	}

	public String encodePage(Instant instant, String tieBreak) {
		if (instant == null) {
			return null;
		}
		if (!StringUtils.hasText(tieBreak)) {
			return instant.toString();
		}
		String payload = instant + "|" + tieBreak;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
	}

	/** Instante da ultima linha entregue e o desempate dentro dele (nulo = nao havia). */
	public record PageCursor(Instant instant, String tieBreak) {
	}
}