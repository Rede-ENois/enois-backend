package com.socialcoisa.rede.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova de boot: filtros de servlet são instanciados antes do contexto completar, então um filtro
 * que puxe no construtor um bean cuja cadeia chega ao EntityManager derruba o Tomcat em produção e
 * NÃO é visto por teste MockMvc (o contexto de teste já está completo quando o filtro nasce).
 * O contexto aqui é um servlet real em porta aleatória, a mesma ordem do deploy.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AdminAccessBaselineBootTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void oFiltroBaselineBootaSemPuxarOCadeiaDeSessaoNoConstrutor() {
		assertThat(context.getBean(AdminAccessBaselineFilter.class)).isNotNull();
	}
}
