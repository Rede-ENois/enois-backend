package com.socialcoisa.rede.emotes.domain;

import java.util.regex.Pattern;

/**
 * Uma figurinha do catalogo: o CODIGO que entra no texto (`KEKW`), de quem ela e e o identificador
 * no CDN do provedor.
 *
 * O endereco da imagem NAO fica gravado aqui. Ele e montado a partir do provedor + identificador
 * (ver `EmoteImages`), o que mantem o catalogo independente de host e de escala — e impede que um
 * catalogo de terceiro consiga apontar a imagem para um servidor qualquer.
 *
 * `popularRank` marca as figurinhas do retrato popular e guarda a posicao de uso (1 = mais usada);
 * `null` quer dizer "so da colecao global do provedor".
 */
public record Emote(String code, EmoteProvider provider, String id, boolean animated, Integer popularRank) {

	/**
	 * Codigo aceito no texto: letra, numero, `_` e `-`. Fora disso a figurinha nao entra no catalogo,
	 * porque um codigo com espaco ou pontuacao nunca seria reconhecido pelo texto de um post.
	 */
	private static final Pattern CODIGO = Pattern.compile("[\\w-]{1,64}");

	/** Identificador do CDN: os dois provedores usam numero ou hash curto. Nada de caminho. */
	private static final Pattern IDENTIFICADOR = Pattern.compile("[A-Za-z0-9]{1,40}");

	public Emote {
		if (code == null || !CODIGO.matcher(code).matches()) {
			throw new IllegalArgumentException("Codigo de figurinha invalido");
		}
		if (provider == null) {
			throw new IllegalArgumentException("Figurinha sem provedor");
		}
		if (id == null || !IDENTIFICADOR.matcher(id).matches()) {
			throw new IllegalArgumentException("Identificador de figurinha invalido");
		}
		if (popularRank != null && popularRank < 1) {
			throw new IllegalArgumentException("Posicao popular invalida");
		}
	}

	public boolean popular() {
		return popularRank != null;
	}

	/** A mesma figurinha com a posicao de uso (o retrato popular manda na ordem do seletor). */
	public Emote popularAt(int rank) {
		return new Emote(code, provider, id, animated, rank);
	}
}