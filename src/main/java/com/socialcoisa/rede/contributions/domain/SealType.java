package com.socialcoisa.rede.contributions.domain;

import java.util.Arrays;
import java.util.List;

/**
 * Níveis de reconhecimento por categoria.
 *
 * <p>O selo representa a conquista, não um privilégio: nada aqui dá poder de moderação. Cada
 * categoria tem uma progressão; a pessoa exibe apenas o nível mais alto que alcançou, e o
 * histórico das conquistas anteriores fica nos eventos de auditoria.</p>
 *
 * <p>Os limites são inclusivos, comme na tabela pública de critérios. O nível é derivado da
 * contagem de contribuições verificadas — nunca de um contador digitado pelo cliente.</p>
 */
public enum SealType {

	AJUDANTE(ContributionCategory.CODE, "Ajudante", 1, 10,
		"Reconhece de 1 a 10 pull requests integrados nos repositórios oficiais."),
	CONTRIBUINTE_SERIO(ContributionCategory.CODE, "Contribuinte sério", 11, 50,
		"Reconhece de 11 a 50 pull requests integrados nos repositórios oficiais."),
	HARDCORE(ContributionCategory.CODE, "Hardcore", 51, 100,
		"Reconhece de 51 a 100 pull requests integrados nos repositórios oficiais."),
	TRYHARD(ContributionCategory.CODE, "Tryhard", 101, 1000,
		"Reconhece de 101 a 1.000 pull requests integrados nos repositórios oficiais."),
	GET_A_LIFE(ContributionCategory.CODE, "Get a life", 1001, Integer.MAX_VALUE,
		"Reconhece mais de 1.000 pull requests integrados nos repositórios oficiais."),

	BETA_TESTER(ContributionCategory.ISSUES, "Beta tester", 1, 10,
		"Reconhece de 1 a 10 issues qualificadas nos repositórios oficiais."),
	EXIGENTE(ContributionCategory.ISSUES, "Exigente", 11, 20,
		"Reconhece de 11 a 20 issues qualificadas nos repositórios oficiais."),
	ESFORCO_MAXIMO(ContributionCategory.ISSUES, "Esforço máximo", 21, 50,
		"Reconhece de 21 a 50 issues qualificadas nos repositórios oficiais."),
	PLUS_ULTRA(ContributionCategory.ISSUES, "Plus ultra", 51, 100,
		"Reconhece de 51 a 100 issues qualificadas nos repositórios oficiais."),
	GENKIDAMA(ContributionCategory.ISSUES, "Genkidama", 101, Integer.MAX_VALUE,
		"Reconhece mais de 100 issues qualificadas nos repositórios oficiais.");

	private final ContributionCategory category;
	private final String displayName;
	private final int minCount;
	private final int maxCount;
	private final String description;

	SealType(ContributionCategory category, String displayName, int minCount, int maxCount,
			String description) {
		this.category = category;
		this.displayName = displayName;
		this.minCount = minCount;
		this.maxCount = maxCount;
		this.description = description;
	}

	public ContributionCategory category() {
		return category;
	}

	public String displayName() {
		return displayName;
	}

	public int minCount() {
		return minCount;
	}

	public int maxCount() {
		return maxCount;
	}

	public String description() {
		return description;
	}

	/** O nível exibido: o mais alto cuja faixa cobre a contagem; zero não tem selo. */
	public static SealType forCount(ContributionCategory category, int count) {
		if (count <= 0) {
			return null;
		}
		SealType selected = null;
		for (SealType type : values()) {
			if (type.category == category && count >= type.minCount) {
				selected = type;
			}
		}
		return selected;
	}

	public static List<SealType> of(ContributionCategory category) {
		return Arrays.stream(values()).filter(type -> type.category == category).toList();
	}
}
