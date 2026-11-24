package com.socialcoisa.rede.talentbank.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O cursor das listas do banco de talentos.
 *
 * <p>Carrega o par (instante, id): o instante sozinho repetiria as linhas empatadas. A chave é
 * base64url, sem estado no servidor — mudar o filtro no meio da navegação reinicia a lista, e
 * isso é melhor do que uma página misturada.</p>
 */
public final class TalentBankCursor {

	private TalentBankCursor() {
	}

	public static Cursor decode(String raw) {
		if (raw == null || raw.isBlank()) {
			return new Cursor(null, null);
		}
		try {
			String payload = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
			int separator = payload.indexOf('|');
			if (separator <= 0) {
				throw new IllegalArgumentException("cursor sem desempate");
			}
			return new Cursor(Instant.parse(payload.substring(0, separator)), payload.substring(separator + 1));
		}
		catch (RuntimeException invalid) {
			throw new ApiException(400, "INVALID_CURSOR", "Cursor inválido.");
		}
	}

	public static String encode(Instant instant, String id) {
		String payload = instant + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
	}

	/** O instante e o id da última linha entregue. */
	public record Cursor(Instant instant, String id) {
	}
}
