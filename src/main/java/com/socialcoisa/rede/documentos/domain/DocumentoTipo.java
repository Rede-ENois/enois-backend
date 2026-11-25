package com.socialcoisa.rede.documentos.domain;

import java.util.Locale;
import java.util.Optional;

/** Os documentos legais versionados. Cada tipo tem a sua própria linha do tempo de versões. */
public enum DocumentoTipo {
	/** Diretrizes da Comunidade: o que pode e o que não pode, e o que acontece quando não pode. */
	DIRETRIZES,
	/** Termos de Uso: o contrato entre a pessoa e a plataforma. */
	TERMOS_DE_USO;

	public static Optional<DocumentoTipo> de(String valor) {
		if (valor == null) {
			return Optional.empty();
		}
		String normalizado = valor.trim().toUpperCase(Locale.ROOT);
		for (DocumentoTipo tipo : values()) {
			if (tipo.name().equals(normalizado)) {
				return Optional.of(tipo);
			}
		}
		return Optional.empty();
	}
}
