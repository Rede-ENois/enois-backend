package com.socialcoisa.rede.wrapped.domain;

import java.util.List;

/**
 * A retrospectiva já reconhecida de UM ano de UMA conta: o conjunto de seções que o design system
 * desenha em sequência. É o RESULTADO persistido (serializado em JSON), sem nenhuma consulta
 * analítica na leitura da tela.
 *
 * <p>Uma seção pode estar vazia (lista vazia ou {@code null}) porque a conta não teve dado
 * suficiente para aquela métrica — o briefing proíbe inventar. A view esconde a seção vazia e a
 * experiência reduzida é honesta. {@code dadosInsuficientes} sinaliza que quase nada havia e a
 * retrospectiva deve se apresentar de forma curta.</p>
 */
public record WrappedRetrospectiva(
		int ano,
		int versaoAlgoritmo,
		boolean dadosInsuficientes,
		WrappedMetricas metricas,
		List<WrappedAssunto> assuntosPublicados,
		List<WrappedAssunto> assuntosComentados,
		List<WrappedPublicacao> publicacoesMarcantes,
		List<WrappedTreta> tretas,
		List<WrappedInteracao> interacoes,
		WrappedRitmo ritmo,
		WrappedPerfil perfil,
		WrappedImpacto impacto,
		List<WrappedComunidade> comunidades,
		WrappedEncerramento encerramento) {
}
