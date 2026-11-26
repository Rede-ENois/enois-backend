package com.socialcoisa.rede.wrapped.domain;

import java.util.List;

/**
 * O perfil de participação: rótulos objetivos derivados de razões medidas, nunca atribuídos ao
 * acaso. Pode haver mais de um. {@code principal} é o de maior pontuação (o mais característico).
 *
 * <p>O rótulo é uma CHAVE estável ({@code DEBATEDOR}, {@code ESPECIALISTA}, {@code OBSERVADOR},
 * {@code CRIADOR}, {@code COMUNITARIO}, {@code SUBVERSIVO}); o texto exibido é do design system
 * (microcopy), não inventado no backend. {@code motivo} traz o número que sustentou a escolha,
 * para a auditoria e para a pessoa entender.</p>
 */
public record WrappedPerfil(Chave principal, List<Caracteristica> caracteristicas) {

	public enum Chave {
		DEBATEDOR,
		ESPECIALISTA,
		OBSERVADOR,
		CRIADOR,
		COMUNITARIO,
		SUBVERSIVO
	}

	public record Caracteristica(Chave chave, String motivo) {
	}
}
