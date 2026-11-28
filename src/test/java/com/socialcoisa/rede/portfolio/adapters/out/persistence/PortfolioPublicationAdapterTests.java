package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.portfolio.domain.PortfolioPublication;

/**
 * O HTML que a lista pública lê: só as versões pedidas, e versão de um dono nunca serve para outro.
 */
@SpringBootTest
class PortfolioPublicationAdapterTests {

	@Autowired
	private PortfolioPublicationAdapter adapter;

	@Test
	void soTrazAsVersoesPedidasDoDonoPedido() {
		var owner = "pf_pub_so_no_ar";
		save(owner, 1);
		save(owner, 2);
		save(owner, 3);
		save("pf_pub_outro_dono", 2);

		Map<String, Map<Integer, String>> html = adapter.publishedHtml(Map.of(owner, 2));

		assertThat(html).containsOnlyKeys(owner);
		assertThat(html.get(owner)).containsOnlyKeys(2);
		assertThat(html.get(owner).get(2)).isEqualTo("<h1>Versao 2</h1>");
	}

	/**
	 * O caso que separa "par (dono, versão)" de "produto cartesiano": dois donos com versões
	 * diferentes na mesma chamada. Filtrando donos e versões como dois conjuntos soltos, a consulta
	 * devolvia a × {1, 3} ∪ b × {1, 3} (quatro linhas) e esta asserção falhava — a leitura até
	 * descartava as extras, mas o banco lia o html delas.
	 */
	@Test
	void doisDonosComVersoesDiferentesTrazemSoOParPedido() {
		save("pf_pub_a", 1);
		save("pf_pub_b", 3);

		Map<String, Map<Integer, String>> html = adapter.publishedHtml(Map.of("pf_pub_a", 1, "pf_pub_b", 3));

		assertThat(html).containsOnlyKeys("pf_pub_a", "pf_pub_b");
		assertThat(html.get("pf_pub_a")).containsOnlyKeys(1);
		assertThat(html.get("pf_pub_b")).containsOnlyKeys(3);
	}

	@Test
	void versaoDeUmDonoNaoServeParaOutro() {
		save("pf_pub_c", 2);
		// O dono d não tem a versão 2: pedir o par dele não pode trazer a linha do c.
		Map<String, Map<Integer, String>> html = adapter.publishedHtml(
			new java.util.LinkedHashMap<>(Map.of("pf_pub_c", 2, "pf_pub_d", 2)));

		assertThat(html).containsOnlyKeys("pf_pub_c");
	}

	@Test
	void semDonosOuSemVersoesNaoConsulta() {
		assertThat(adapter.publishedHtml(Map.of())).isEmpty();
		assertThat(adapter.publishedHtml(null)).isEmpty();
	}

	private void save(String userId, int version) {
		adapter.save(new PortfolioPublication(userId + "-" + version, userId, version,
			"<h1>Versao " + version + "</h1>", List.of(), Instant.parse("2026-11-27T00:00:00Z")));
	}
}