package com.socialcoisa.rede.shared.security;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Recusa repeticao da mesma conta, texto e contexto validado, com 409 DUPLICATE_CONTENT.
 * A janela continua configuravel por {@code rede.rate.duplicate-window-minutes} (60; 0 desliga).
 * O cache guarda apenas hashes, limita memoria e libera a reserva se a transacao for desfeita.
 */
@Component
public class ContentDuplicationGuard {

	private static final int MAX_ENTRIES = 50_000;

	private final Cache<String, Object> seen;

	public ContentDuplicationGuard(
			@Value("${rede.rate.duplicate-window-minutes:60}") long windowMinutes) {
		if (windowMinutes > 0) {
			this.seen = Caffeine.newBuilder()
				.expireAfterWrite(Duration.ofMinutes(windowMinutes))
				.maximumSize(MAX_ENTRIES)
				.build();
		}
		else {
			this.seen = null;
		}
	}

	/**
	 * O mesmo texto já saiu desta conta na janela? (consulta pura: não registra nada)
	 *
	 * <p>É o que o motor de risco pergunta para combinar sinais; o {@code require} abaixo continua
	 * sendo quem recusa a publicação e quem guarda o texto.
	 */
	public boolean isDuplicate(String userId, String content, String... contexto) {
		if (seen == null) {
			return false;
		}
		return seen.getIfPresent(chave(userId, content, contexto)) != null;
	}

	/**
	 * Verifica se o conteúdo já foi publicado por este usuário recentemente. Lança 409 se duplicado.
	 */
	public void require(String userId, String content, String... contexto) {
		if (seen == null) {
			return;
		}
		String key = chave(userId, content, contexto);
		Object reserva = new Object();
		if (seen.asMap().putIfAbsent(key, reserva) != null) {
			throw new ApiException(409, "DUPLICATE_CONTENT",
				"Voce ja publicou este conteudo neste contexto recentemente.");
		}
		liberarSeDesfeita(key, reserva);
	}

	private void liberarSeDesfeita(String chave, Object reserva) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()
				|| !TransactionSynchronizationManager.isActualTransactionActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					seen.asMap().remove(chave, reserva);
				}
			}
		});
	}

	private static String chave(String userId, String content, String[] contexto) {
		StringBuilder partes = new StringBuilder();
		for (String campo : List.of(userId, normalize(content))) {
			partes.append(campo.length()).append(':').append(campo);
		}
		for (String campo : contexto) {
			partes.append(campo.length()).append(':').append(campo);
		}
		return Hashes.sha256Hex(partes.toString());
	}

	private static String normalize(String text) {
		return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}
}
