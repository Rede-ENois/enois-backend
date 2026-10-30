package com.socialcoisa.rede.emotes.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Politica de nome do catalogo: figurinha cujo CODIGO remete a conteudo sexual nao entra.
 *
 * A normalizacao existe porque um codigo pode chegar escrito de qualquer jeito (letras trocadas por
 * numero, acento, separador) e o nome "parecido" tem que cair na mesma regra que o exato. Termos
 * fortes casam como substring (um composto tipo "bigbutt" tem que ser pego); termos fracos, que
 * tambem existem em nomes innocuos, so casam como palavra inteira, para nao barrar figurinha que nao
 * tem nada a ver.
 */
public final class EmoteNamePolicy {

	private EmoteNamePolicy() {
	}

	/** Casam em qualquer lugar do codigo: remetem a conteudo sexual por si so. */
	private static final List<String> FORTES = List.of("dick", "porn", "nude", "nudes", "boob", "titt", "titty",
			"pussy", "vagina", "penis", "anal", "orgasm", "handjob", "blowjob", "jerkoff", "hentai", "cumshot",
			"jizz", "cumm", "sexy", "booty", "butt", "eggplant", "poggies", "peitos", "punheta", "buceta",
			"transar");

	/** So como palavra inteira: sem eles, nomes como "showdown" ou "plantfood" cairiam sem motivo. */
	private static final List<String> FRACOS = List.of("egg", "plant", "show", "sex", "naked", "pelada", "pelado");

	/** Nomes innocuos que contem um termo forte por acidente. */
	private static final List<String> EXCECOES = List.of("button", "butter", "butterfly");

	public static boolean blocked(String code) {
		String normalizado = normalize(code);
		if (normalizado.isBlank()) {
			return false;
		}
		for (String innocuo : EXCECOES) {
			if (normalizado.contains(innocuo)) {
				return false;
			}
		}
		for (String termo : FORTES) {
			if (normalizado.contains(termo)) {
				return true;
			}
		}
		for (String termo : FRACOS) {
			if (normalizado.equals(termo)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Minusculas, sem acento, sem separador e com as trocas mais comuns de letra por numero/simbolo
	 * desfeitas — e isso que faz o "parecido" cair na mesma regra do exato.
	 */
	static String normalize(String code) {
		if (code == null) {
			return "";
		}
		String semAcento = Normalizer.normalize(code.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "");
		StringBuilder letras = new StringBuilder(semAcento.length());
		for (char c : semAcento.toCharArray()) {
			letras.append(switch (c) {
				case '0' -> 'o';
				case '1', '!', '|' -> 'i';
				case '3' -> 'e';
				case '4', '@' -> 'a';
				case '5', '$' -> 's';
				case '7' -> 't';
				default -> c;
			});
		}
		return letras.toString().replaceAll("[^a-z]", "");
	}

}
