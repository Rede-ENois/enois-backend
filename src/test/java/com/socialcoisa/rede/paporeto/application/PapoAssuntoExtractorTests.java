package com.socialcoisa.rede.paporeto.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * O extrator de assuntos: dobragem, stopwords, n-gramas e clustering. Cada teste prende uma
 * regra do jogo — o que vira assunto, o que nunca vira, e o que é o MESMO assunto com roupa
 * diferente.
 */
class PapoAssuntoExtractorTests {

	private final PapoAssuntoExtractor extractor = new PapoAssuntoExtractor();

	@Test
	void dobraAcentoECaixa() {
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("Inteligência Artificial mudou tudo",
				"POST");

		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao)
			.contains("inteligencia artificial", "inteligencia", "artificial");
	}

	@Test
	void palavrasVaziasETokensPequenosNaoViramAssunto() {
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("eu to na praia com o cachorro dela", "POST");

		// As vazias ("eu", "to", "com", "o", "dela") saem; e "praia" e "cachorro" NÃO colam
		// em bigrama porque no texto têm "com o" no meio — vizinhança é no texto original.
		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao).containsExactly("praia", "cachorro");
	}

	@Test
	void aMesmaPalavraNoMesmoTextoContaUmaVezSo() {
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("praia praia praia", "POST");

		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao).containsExactly("praia");
	}

	@Test
	void textoSemPalavraElegivelNaoGeraAssunto() {
		assertThat(extractor.extrair("bom dia!!!", "POST")).isEmpty();
		assertThat(extractor.extrair("   ", "POST")).isEmpty();
		assertThat(extractor.extrair(null, "POST")).isEmpty();
	}

	@Test
	void palavraGiganteNaoViraAssunto() {
		// O bagaço de 5000 letras (spam, chave colada, gritaria) não é conversa de ninguém:
		// não vira fala e não estoura a coluna da expressão.
		String bagaco = "y".repeat(5000);

		assertThat(extractor.extrair(bagaco, "POST")).isEmpty();
		assertThat(extractor.extrair("meetup " + bagaco + " na praia", "POST"))
			.extracting(PapoAssuntoExtractor.Fala::expressao)
			.containsExactly("meetup", "praia");
	}

	@Test
	void bigramaVenceUnigramaNoCluster() {
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("inteligencia artificial na praia", "POST");

		List<String> assuntos = extractor.assuntos(falas);
		assertThat(assuntos).containsExactlyInAnyOrder("inteligencia artificial", "praia");
	}

	@Test
	void pontuacaoNaoColaPalavra() {
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("feei o meetup, bora?", "POST");

		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao)
			.contains("meetup")
			.doesNotContain("feei meetup");
	}

	@Test
	void linkMencaoEmoteERisoNaoViramAssunto() {
		// Foi o bug de produção: o "http" do endereço colado virava alta da rede.
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair(
				"olha http://enois.ia.br/papo?utm=abc @mel :KEKW: kkkkk hahaha 2026", "POST");

		assertThat(falas).isEmpty();
	}

	@Test
	void rotuloDoLinkEhConteudoMasOEnderecoSai() {
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("[guia de rust](https://enois.ia.br/rust)",
				"POST");

		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao)
			.contains("guia", "rust")
			.doesNotContain("enois");
	}

	@Test
	void tagDeclaradaViraAssuntoDobrado() {
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("foto do setup", "POST",
				List.of("Home Office", "http://exemplo.com"));

		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao)
			.contains("home office")
			.doesNotContain("http");
	}

	@Test
	void assuntoValidoRecusaRuidoNaLeitura() {
		assertThat(extractor.assuntoValido("http")).isFalse();
		assertThat(extractor.assuntoValido("kkkk")).isFalse();
		assertThat(extractor.assuntoValido("2026")).isFalse();
		assertThat(extractor.assuntoValido("")).isFalse();
		assertThat(extractor.assuntoValido("inteligencia artificial")).isTrue();
		assertThat(extractor.assuntoValido("home office")).isTrue();
	}

}
