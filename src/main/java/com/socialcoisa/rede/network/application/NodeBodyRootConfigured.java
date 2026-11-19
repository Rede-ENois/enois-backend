package com.socialcoisa.rede.network.application;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * A propriedade {@code rede.storage.node-body-root} está de fato preenchida.
 *
 * <p>{@code @ConditionalOnProperty} casa com a propriedade PRESENTE, mesmo vazia — e o padrão em
 * {@code application.properties} ({@code ${REDE_STORAGE_NODE_BODY_ROOT:}}) deixa a propriedade
 * presente em todo ambiente, vazia onde a variável não existe. Vazio é desligado: o
 * {@link NodeBodyStore} só deve existir quando há um diretório de verdade. Foi a diferença entre
 * os dois que derrubou a QA em 2026-11-16: condição ligada por propriedade vazia + recusa de
 * valor vazio no construtor = crash loop com a API fora do ar a cada deploy.
 */
class NodeBodyRootConfigured implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		String root = context.getEnvironment().getProperty("rede.storage.node-body-root", "");
		return !root.isBlank();
	}
}
