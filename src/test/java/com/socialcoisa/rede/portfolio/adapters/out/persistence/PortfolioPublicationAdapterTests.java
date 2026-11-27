package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

		Map<String, Map<Integer, String>> html = adapter.publishedHtml(List.of(owner), Set.of(2));

		assertThat(html).containsOnlyKeys(owner);
		assertThat(html.get(owner)).containsOnlyKeys(2);
		assertThat(html.get(owner).get(2)).isEqualTo("<h1>Versao 2</h1>");
	}

	@Test
	void semDonosOuSemVersoesNaoConsulta() {
		assertThat(adapter.publishedHtml(List.of(), Set.of(1))).isEmpty();
		assertThat(adapter.publishedHtml(List.of("pf_pub_nada"), Set.of())).isEmpty();
	}

	private void save(String userId, int version) {
		adapter.save(new PortfolioPublication(userId + "-" + version, userId, version,
			"<h1>Versao " + version + "</h1>", List.of(), Instant.parse("2026-11-27T00:00:00Z")));
	}
}