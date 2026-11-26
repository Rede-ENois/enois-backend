package com.socialcoisa.rede.wrapped.domain;

/**
 * O fecho do ano: uma frase montada de dados reais (tema principal, característica, estatística)
 * e a mensagem de agradecimento da comunidade.
 *
 * <p>A frase é composta aqui, mas das PEÇAS medidas ({@code temaPrincipal}, {@code caracteristica},
 * {@code estatistica}). O design system tem o fraseado fixo e traduz as peças; o backend nunca
 * inventa um fato que não encontrou. Quando não há peça suficiente, o campo fica sem a peça —
 * a view escolhe uma frase honesta em vez de preencher com dado falso.</p>
 */
public record WrappedEncerramento(
		String temaPrincipal,
		WrappedPerfil.Chave caracteristica,
		String estatistica) {
}
