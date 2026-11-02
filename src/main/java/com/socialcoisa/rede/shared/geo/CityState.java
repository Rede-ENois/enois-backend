package com.socialcoisa.rede.shared.geo;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.TextFieldLimit;

/**
 * Cidade + UF estruturados (comunidades locais, perfil, eventos presenciais).
 *
 * A cidade fica como a pessoa escreveu (aparada), e o `cityKey` e a forma de comparar: sem acento,
 * minuscula, espacos colapsados. "São Paulo" e "sao  paulo" viram a mesma chave. A chave e gravada
 * numa coluna propria porque collation e funcao de acento diferem entre H2 e MariaDB, e coluna
 * gravada e indexavel nos dois.
 */
public record CityState(String city, String state, String cityKey) {

	public static final int CITY_MAX = 80;

	/** As 27 unidades federativas. */
	public static final Set<String> STATES = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA",
		"MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");

	/**
	 * Os dois ou nenhum. Os dois em branco = sem local (null); so um preenchido = 400. Cidade sem UF
	 * e ambigua (ha "Bom Jesus" em cinco estados), UF sem cidade nao serve para encontro presencial.
	 */
	public static CityState of(String city, String state) {
		boolean hasCity = city != null && !city.isBlank();
		boolean hasState = state != null && !state.isBlank();
		if (!hasCity && !hasState) {
			return null;
		}
		if (hasCity != hasState) {
			throw new ApiException(400, "CITY_STATE_INCOMPLETE", "Informe a cidade e o estado juntos.");
		}
		String trimmedCity = city.trim().replaceAll("\\s+", " ");
		TextFieldLimit.check("CITY_TOO_LONG", "cidade", trimmedCity, CITY_MAX);
		return new CityState(trimmedCity, requireState(state), key(trimmedCity));
	}

	/** UF valida, em maiusculas; recusa o que nao e uma das 27. */
	public static String requireState(String state) {
		String uf = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
		if (!STATES.contains(uf)) {
			throw new ApiException(400, "INVALID_STATE", "Estado invalido. Use a sigla da UF, como MG ou SP.");
		}
		return uf;
	}

	/** Chave de comparacao: sem acento, minuscula, espacos colapsados. */
	public static String key(String city) {
		if (city == null || city.isBlank()) {
			return null;
		}
		String noMarks = Normalizer.normalize(city.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

}
