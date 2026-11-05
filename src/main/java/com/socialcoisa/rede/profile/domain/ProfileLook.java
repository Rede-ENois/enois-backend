package com.socialcoisa.rede.profile.domain;

/**
 * Ajustes visuais de UM perfil (o social ou o profissional): o preset escolhido e o que foi
 * mexido por cima dele.
 *
 * Todos os campos nascem nulos de propósito — nulo significa "não escolheu" e o front aplica
 * o padrão da plataforma. Os valores aceitos são uma lista fechada (ids do catálogo de temas,
 * cor hex, nomes de fonte, estilos de cabeçalho/borda e um nível curto de decoração): nada aqui
 * vira CSS livre, então não existe caminho para injetar regra arbitrária na página.
 */
public record ProfileLook(String theme, String accent, String font, String header, Boolean textures,
		String borders, Integer decoration) {

	public static ProfileLook empty() {
		return new ProfileLook(null, null, null, null, null, null, null);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public boolean isEmpty() {
		return theme == null && accent == null && font == null && header == null && textures == null
				&& borders == null && decoration == null;
	}
}
