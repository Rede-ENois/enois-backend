package com.socialcoisa.rede.jobs.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Compatibilidade entre o perfil de quem procura e uma vaga, de 0 a 100.
 *
 * <p>Só entra no cálculo o que os DOIS lados informam. Perfil sem habilidades não ganha nem perde
 * ponto por habilidade: o sinal simplesmente não existe, e o peso é redistribuído entre os sinais
 * que existem. Sem nenhum sinal em comum o resultado é 0, e a tela não mostra "0% match".</p>
 *
 * <p>O que NÃO é calculado, de propósito: senioridade e faixa salarial. O perfil não declara
 * senioridade nem pretensão, e deduzi-las do histórico ou do texto seria inventar dado sobre a
 * pessoa. Quando o perfil passar a declarar essas preferências, elas entram aqui como sinal — não
 * antes.</p>
 *
 * <p>Nas vagas externas a fonte raramente lista habilidades: aí o sinal de tecnologia vem do texto
 * do anúncio (título e descrição), procurando a habilidade do perfil como palavra inteira. Isso é
 * relevância calculada sobre o que a fonte publicou, não uma afirmação de que a vaga exige aquilo.</p>
 */
public record JobCompatibility(List<String> skills, String city, String state) {

	/** Peso do sinal de tecnologias no total. */
	static final int SKILLS_WEIGHT = 60;

	/** Peso do sinal de localidade no total. */
	static final int LOCATION_WEIGHT = 40;

	/**
	 * Quantas habilidades do perfil citadas no anúncio já contam como aderência alta quando a vaga
	 * não lista requisitos. Quatro é o ponto em que o anúncio claramente fala do que a pessoa faz.
	 */
	static final int TEXT_SKILLS_FULL = 4;

	/** Habilidades de uma ou duas letras ("C", "R", "Go") casariam com qualquer texto. */
	private static final int MIN_SKILL_LENGTH = 2;

	public static JobCompatibility of(List<String> skills, String city, String state) {
		List<String> clean = skills == null ? List.of()
			: skills.stream()
				.filter(skill -> skill != null && !skill.isBlank())
				.map(String::trim)
				.toList();
		return new JobCompatibility(clean, blankToNull(city), blankToNull(state));
	}

	/**
	 * @param jobSkills o que a vaga lista como requisito (vaga interna); vazio nas externas
	 * @param jobText título e descrição, usados quando a vaga não lista habilidades
	 * @param jobCity cidade informada pela fonte (nula quando ela não separa a localidade)
	 * @param jobState UF informada pela fonte
	 * @param workModel REMOTE, HYBRID, ONSITE ou nulo
	 */
	public int scoreOf(List<String> jobSkills, String jobText, String jobCity, String jobState, String workModel) {
		int weight = 0;
		double total = 0;
		Double skillsScore = skillsScore(jobSkills, jobText);
		if (skillsScore != null) {
			total += skillsScore * SKILLS_WEIGHT;
			weight += SKILLS_WEIGHT;
		}
		Double locationScore = locationScore(jobCity, jobState, workModel);
		if (locationScore != null) {
			total += locationScore * LOCATION_WEIGHT;
			weight += LOCATION_WEIGHT;
		}
		if (weight == 0) {
			return 0;
		}
		return (int) Math.round(total / weight * 100);
	}

	/**
	 * Fração do que a vaga pede que o perfil cobre. Nulo quando não há como comparar: perfil sem
	 * habilidades, ou vaga sem requisitos e sem texto.
	 */
	private Double skillsScore(List<String> jobSkills, String jobText) {
		if (skills.isEmpty()) {
			return null;
		}
		List<String> required = jobSkills == null ? List.of()
			: jobSkills.stream()
				.filter(skill -> skill != null && !skill.isBlank())
				.map(String::trim)
				.toList();
		if (!required.isEmpty()) {
			long matched = required.stream().filter(this::covers).count();
			return (double) matched / required.size();
		}
		String text = normalize(jobText);
		if (text.isBlank()) {
			return null;
		}
		long matched = skills.stream()
			.map(JobCompatibility::normalize)
			.filter(skill -> skill.length() >= MIN_SKILL_LENGTH && containsWord(text, skill))
			.count();
		return Math.min(1.0, (double) matched / Math.min(skills.size(), TEXT_SKILLS_FULL));
	}

	/**
	 * Localidade: vaga remota serve para qualquer lugar; presencial/híbrida vale pela cidade e,
	 * na falta dela, pela UF. Nulo quando a vaga não informa localidade e não é remota.
	 *
	 * A comparação é por palavra dentro do texto da localidade, porque a vaga interna guarda o
	 * lugar como texto livre ("São Paulo, SP") e a externa às vezes separa cidade e UF.
	 */
	private Double locationScore(String jobCity, String jobState, String workModel) {
		if ("REMOTE".equals(workModel)) {
			return 1.0;
		}
		String jobLocation = (normalize(jobCity) + " " + normalize(jobState)).trim();
		if (jobLocation.isBlank() || (city == null && state == null)) {
			return null;
		}
		if (city != null && containsWord(jobLocation, normalize(city))) {
			return 1.0;
		}
		if (state != null && containsWord(jobLocation, normalize(state))) {
			return 0.5;
		}
		return 0.0;
	}

	/** O perfil cobre este requisito? Igualdade normalizada ou um contendo o outro como palavra. */
	private boolean covers(String required) {
		String key = normalize(required);
		if (key.isBlank()) {
			return false;
		}
		for (String skill : skills) {
			String mine = normalize(skill);
			if (mine.length() < MIN_SKILL_LENGTH) {
				continue;
			}
			if (mine.equals(key) || containsWord(key, mine) || containsWord(mine, key)) {
				return true;
			}
		}
		return false;
	}

	/** Palavra inteira: "java" não casa com "javascript", mas "react" casa com "react.js". */
	private static boolean containsWord(String text, String word) {
		if (text.isBlank() || word.isBlank()) {
			return false;
		}
		return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(word) + "(?![a-z0-9])").matcher(text).find();
	}

	/** Sem acento, minúscula, espaços colapsados — a mesma forma dos dois lados da comparação. */
	static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String noMarks = Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
