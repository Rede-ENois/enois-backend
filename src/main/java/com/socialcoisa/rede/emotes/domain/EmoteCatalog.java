package com.socialcoisa.rede.emotes.domain;

import java.time.Instant;
import java.util.List;

/**
 * O catalogo inteiro num instante: a lista ja ordenada (populares por uso, depois o resto em
 * ordem alfabetica) e o horario da montagem.
 *
 * O `etag` sai do conteudo: enquanto nada muda, todo mundo que ja tem a copia recebe `304`. Como a
 * montagem de 3 em 3 horas recalcula o horario, o etag muda a cada montagem mesmo sem figurinha
 * nova — e e isso que faz o app do leitor pegar a lista atualizada sem visitar os provedores.
 */
public record EmoteCatalog(List<Emote> emotes, Instant generatedAt) {

	public EmoteCatalog {
		emotes = List.copyOf(emotes);
	}

	public static EmoteCatalog empty() {
		return new EmoteCatalog(List.of(), Instant.EPOCH);
	}

	public boolean isEmpty() {
		return emotes.isEmpty();
	}

	public String etag() {
		return "W/\"" + Integer.toHexString(emotes.hashCode()) + "-" + generatedAt.getEpochSecond() + "\"";
	}
}