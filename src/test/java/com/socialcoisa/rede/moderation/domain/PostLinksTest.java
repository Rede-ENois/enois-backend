package com.socialcoisa.rede.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class PostLinksTest {

	@Test
	void extraiOsEnderecosDoTexto() {
		List<URI> links = PostLinks.extract(
			"olha esse video https://www.youtube.com/watch?v=abc123 e depois https://enois.ia.br/blog");

		assertThat(links).containsExactly(URI.create("https://youtube.com/watch?v=abc123"),
			URI.create("https://enois.ia.br/blog"));
	}

	@Test
	void ignoraEsquemaQueNaoEDestino() {
		assertThat(PostLinks.extract("javascript:alert(1) e data:text/html,oi")).isEmpty();
		assertThat(PostLinks.extract("file:///etc/passwd")).isEmpty();
		assertThat(PostLinks.extract("ftp://exemplo.com/arquivo")).isEmpty();
	}

	@Test
	void normalizaParaCompararOMesmoDestino() {
		// www, barra final, maiuscula no host e rastreador saem: os quatro apontam para o mesmo lugar.
		URI esperado = URI.create("https://youtube.com/watch?v=abc123");
		assertThat(PostLinks.normalize("https://www.YouTube.com/watch?v=abc123&utm_source=x")).isEqualTo(esperado);
		assertThat(PostLinks.normalize("https://youtube.com/watch/?v=abc123")).isEqualTo(esperado);
		assertThat(PostLinks.normalize("https://youtube.com/watch?v=abc123.")).isEqualTo(esperado);
	}

	@Test
	void mantemParametroQueMudaOConteudo() {
		assertThat(PostLinks.normalize("https://youtube.com/watch?v=abc")).isNotEqualTo(
			PostLinks.normalize("https://youtube.com/watch?v=xyz"));
	}

	@Test
	void cortaNoTetoDeLinks() {
		StringBuilder texto = new StringBuilder();
		for (int i = 0; i < 12; i++) {
			texto.append("https://exemplo").append(i).append(".com/a ");
		}
		assertThat(PostLinks.extract(texto.toString())).hasSize(PostLinks.MAX_LINKS);
	}

	@Test
	void textoVazioOuNuloNaoTemLink() {
		assertThat(PostLinks.extract(null)).isEmpty();
		assertThat(PostLinks.extract("   ")).isEmpty();
		assertThat(PostLinks.extract("sem link nenhum aqui")).isEmpty();
	}

	@Test
	void naoAceitaHostInvalido() {
		assertThat(PostLinks.normalize("https://localhost:8080/admin")).isNull();
		assertThat(PostLinks.normalize("https:///sem-host")).isNull();
	}

}
