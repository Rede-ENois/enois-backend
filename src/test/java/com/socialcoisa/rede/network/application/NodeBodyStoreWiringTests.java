package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * A ligação do {@link NodeBodyStore} é por valor, não por presença.
 *
 * <p>O padrão em {@code application.properties} é {@code ${REDE_STORAGE_NODE_BODY_ROOT:}}: a
 * propriedade está <b>presente</b> em todo ambiente e vazia onde a variável não existe. Era a
 * receita do crash loop da QA (2026-11-16): {@code @ConditionalOnProperty} casa com
 * presente-e-vazia, o bean ligava desligado e o construtor recusava o vazio — o app morria no
 * boot, reiniciava e morria de novo, com a API fora do ar a cada deploy.</p>
 *
 * <p>O contrato aqui: propriedade ausente OU vazia = sem bean (Control Plane puro sobe); valor
 * preenchido = bean criado e apontando para o diretório.</p>
 */
class NodeBodyStoreWiringTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(NodeBodyStore.class);

	@Test
	void propriedade_vazia_nao_liga_o_bean() {
		runner.withPropertyValues("rede.storage.node-body-root=").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(NodeBodyStore.class);
		});
	}

	@Test
	void propriedade_ausente_nao_liga_o_bean() {
		runner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(NodeBodyStore.class);
		});
	}

	@Test
	void propriedade_preenchida_liga_o_bean() {
		runner.withPropertyValues("rede.storage.node-body-root=target/test-node-body-wiring").run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(NodeBodyStore.class);
		});
	}
}
