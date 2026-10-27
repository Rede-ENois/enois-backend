package com.socialcoisa.rede.contributions.domain;

/**
 * Categoria de reconhecimento. CODE e ISSUES têm progressão implementada; as demais já existem
 * no modelo para receber critérios próprios quando houver fonte verificável (documentação,
 * segurança, comunidade, apoio financeiro e infraestrutura). Apoio financeiro NUNCA se mistura
 * com reputação técnica.
 */
public enum ContributionCategory {

	CODE("Código", "Pull requests integrados nos repositórios oficiais do projeto."),
	ISSUES("Issues", "Problemas, melhorias e documentação reconhecidos pelos mantenedores."),
	DOCUMENTATION("Documentação", "Contribuições de documentação verificadas."),
	SECURITY("Segurança", "Vulnerabilidades válidas reportadas e confirmadas."),
	COMMUNITY("Comunidade", "Testes, suporte e outras contribuições comunitárias verificáveis."),
	DONATION("Apoio", "Apoio financeiro confirmado, independente da reputação técnica."),
	INFRASTRUCTURE("Infraestrutura", "Recursos e serviços fornecidos ao projeto.");

	private final String displayName;
	private final String description;

	ContributionCategory(String displayName, String description) {
		this.displayName = displayName;
		this.description = description;
	}

	public String displayName() {
		return displayName;
	}

	public String description() {
		return description;
	}

	/** Só categoria com progressão implementada concede selo; as demais são arquitetura futura. */
	public boolean hasSealTiers() {
		return !SealType.of(this).isEmpty();
	}
}
