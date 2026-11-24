package com.socialcoisa.rede.shared.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CursorCodec {

	/**
	 * Teto do cursor aceito. O cursor legitimo e um ISO-8601 (20-24 chars), o par
	 * instante|desempate em base64url (pouco mais de 50) ou a posicao "o:" com digitos. 256 da a
	 * folga larga e mantem a entrada longe de qualquer buffer grande no stub intrinseco de decode
	 * Base64 (CVE-2026-114938).
	 */
	static final int MAX_CURSOR_LENGTH = 256;

	/** Marca do cursor de posicao; nenhum cursor de instante comeca com ela. */
	static final String OFFSET_PREFIX = "o:";

	/** Alfabeto base64url; recusar antes do decode evita passar lixo ao decoder nativo. */
	private static final java.util.regex.Pattern BASE64URL = java.util.regex.Pattern.compile("[A-Za-z0-9_-]+={0,2}");

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
		if (cursor.length() > MAX_CURSOR_LENGTH) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
		}
		try {
			return Optional.of(new PageCursor(Instant.parse(cursor), null));
		}
		catch (RuntimeException notAnInstant) {
			// Formato composto; se tambem nao decodificar, o cursor e invalido.
			if (!BASE64URL.matcher(cursor).matches()) {
				throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
			}
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

	/**
	 * Cursor de posicao: a proxima pagina comeca depois de {@code offset} linhas da mesma ordenacao
	 * (que precisa ser total, ou seja, com desempate unico). Formato proprio, so para quem usa.
	 */
	public Optional<Long> decodeOffset(String cursor) {
		if (!StringUtils.hasText(cursor)) {
			return Optional.empty();
		}
		if (!cursor.startsWith(OFFSET_PREFIX) || cursor.length() > MAX_CURSOR_LENGTH) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
		}
		try {
			long offset = Long.parseLong(cursor.substring(OFFSET_PREFIX.length()));
			if (offset < 0) {
				throw new IllegalArgumentException("deslocamento negativo");
			}
			return Optional.of(offset);
		}
		catch (IllegalArgumentException invalid) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor invalido.");
		}
	}

	public String encodeOffset(long offset) {
		return OFFSET_PREFIX + offset;
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