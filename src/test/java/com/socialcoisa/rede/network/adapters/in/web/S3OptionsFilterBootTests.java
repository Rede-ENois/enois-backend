package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;

/**
 * Prova de boot do filtro de {@code OPTIONS} do gateway S3.
 *
 * <p>Filtro de servlet nasce quando o Tomcat sobe, antes do contexto completar. Se ele puxar no
 * construtor um bean cuja cadeia chega ao {@code EntityManager} — o controller do gateway chega lá
 * pelo resolvedor de sessão — o container morre no boot, sem uma requisição sequer, e o deploy
 * volta a imagem antiga. MockMvc não vê isso: o contexto de teste já está completo quando o filtro
 * nasce. Aqui o contexto é um servlet real em porta aleatória, a mesma ordem do deploy.</p>
 *
 * <p>O mesmo modo de falha derrubou o deploy da QA em 2026-11-24: o filtro puxava o
 * {@link S3GatewayController} no construtor e o Tomcat não subia.</p>
 */
@Tag("critico")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class S3OptionsFilterBootTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void oFiltroDeOptionsBootaSemPuxarOCadeiaDoGatewayNoConstrutor() {
		assertThat(context.getBean(S3OptionsFilter.class)).isNotNull();
	}
}
