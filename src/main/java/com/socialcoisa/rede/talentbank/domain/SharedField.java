package com.socialcoisa.rede.talentbank.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * O que a pessoa autoriza a compartilhar com a banca em que se inscreve.
 *
 * <p>A inscricao NÃO é uma autorização genérica: cada campo é ligado pelo candidato, na hora, e o
 * recrutador só enxerga os marcados. O conjunto fica gravado na própria inscricao — é a prova do que
 * foi consentido, e não do que o recrutador chegou a ver.</p>
 */
public enum SharedField {

	/** Perfil profissional: nome, @, título, foto e localização. */
	PROFILE,
	/** Competências declaradas. */
	SKILLS,
	/** Experiências profissionais. */
	EXPERIENCES,
	/** Formação. */
	EDUCATION,
	/** Certificações. */
	CERTIFICATIONS,
	/** Currículo (arquivo), servido pelo backend sob autorização. */
	RESUME;

	public String key() {
		return name();
	}

	/** Converte a chave vinda do cliente; chave desconhecida não vira campo. */
	public static Optional<SharedField> byKey(String raw) {
		if (raw == null) {
			return Optional.empty();
		}
		String key = raw.trim().toUpperCase(Locale.ROOT);
		for (SharedField field : values()) {
			if (field.name().equals(key)) {
				return Optional.of(field);
			}
		}
		return Optional.empty();
	}
}
