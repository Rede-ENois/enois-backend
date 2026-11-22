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
	void falaDaRedeNaoViraAssunto() {
		// Foi o bug da tela: "gente", "sei", "tenho", "mundo", "ate" e "enois" apareciam como
		// tendência própria. Eles não nomeiam conversa nenhuma — são a fala de todo mundo.
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair(
				"gente eu sei que tenho muito a dizer sobre o mundo, ate porque o enois e isso", "POST");

		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao)
			.doesNotContain("gente", "sei", "tenho", "mundo", "ate", "enois");
	}

	@Test
	void textoSoDeConversaNaoGeraAssunto() {
		// Conteúdo insuficiente: saudação, riso e abreviação não são conversa.
		assertThat(extractor.extrair("sla, vamo bora nessa, blz?", "POST")).isEmpty();
		assertThat(extractor.extrair("gente, olha isso, muito bom", "POST")).isEmpty();
	}

	@Test
	void expressaoCompostaAtravessaOConectorEOLiga() {
		// "mercado de trabalho" é UMA conversa: o "de" cola as duas palavras, e os unigramas
		// ficam cobertos pela expressão (a tela não lista a mesma conversa três vezes).
		List<String> assuntos = extractor.assuntos(
				extractor.extrair("mercado de trabalho na minha cidade", "POST"));

		assertThat(assuntos).containsExactly("mercado de trabalho", "cidade");
	}

	@Test
	void acentoNaExpressaoCompostaNaoAtrapalha() {
		List<String> assuntos = extractor.assuntos(
				extractor.extrair("Inteligência de dados é o assunto da semana", "POST"));

		assertThat(assuntos).contains("inteligencia de dados");
		assertThat(assuntos).doesNotContain("inteligencia", "dados");
	}

	@Test
	void pareamentoNaoSobrepoeAExpressao() {
		// Cada palavra entra em uma expressão só: "meetup de rust na sexta" é a conversa do
		// meetup (com o "na" não colando o "rust" no "sexta"), e não dois pares contando a
		// mesma publicação duas vezes.
		List<String> assuntos = extractor.assuntos(
				extractor.extrair("meetup de rust na sexta", "POST"));

		assertThat(assuntos).containsExactly("meetup de rust", "sexta");
	}

	@Test
	void conectorForaDaListaNaoColaPalavra() {
		// "com" não é conector de expressão composta: "inteligencia com artificial" são duas
		// palavras no mesmo texto, não um assunto.
		List<String> assuntos = extractor.assuntos(
				extractor.extrair("inteligencia com artificial", "POST"));

		assertThat(assuntos).containsExactly("inteligencia", "artificial");
	}

	@Test
	void assuntoValidoRecusaRuidoNaLeitura() {
		assertThat(extractor.assuntoValido("http")).isFalse();
		assertThat(extractor.assuntoValido("kkkk")).isFalse();
		assertThat(extractor.assuntoValido("2026")).isFalse();
		assertThat(extractor.assuntoValido("")).isFalse();
		assertThat(extractor.assuntoValido("gente")).isFalse();
		assertThat(extractor.assuntoValido("inteligencia artificial")).isTrue();
		assertThat(extractor.assuntoValido("home office")).isTrue();
	}

	@Test
	void assuntoValidoAceitaOConectorSoNoMeioDaExpressao() {
		assertThat(extractor.assuntoValido("mercado de trabalho")).isTrue();
		assertThat(extractor.assuntoValido("de trabalho")).isFalse();
		assertThat(extractor.assuntoValido("mercado de")).isFalse();
		assertThat(extractor.assuntoValido("mercado bla trabalho")).isFalse();
		assertThat(extractor.assuntoValido("de")).isFalse();
	}

	@Test
	void verboNoGerundioIsoladoNaoViraAssunto() {
		// Foi o bug do "fazendo" em alta: a terminação é o que decide (regra da língua), e não
		// uma lista de conjugações — cobre "ando", "endo" e "indo" de qualquer verbo.
		assertThat(extractor.assuntoValido("fazendo")).isFalse();
		assertThat(extractor.assuntoValido("dando")).isFalse();
		assertThat(extractor.assuntoValido("trabalhando")).isFalse();
		assertThat(extractor.extrair("o que voce esta fazendo", "POST")).isEmpty();
		assertThat(extractor.extrair("dando voltas sem parar", "POST"))
			.extracting(PapoAssuntoExtractor.Fala::expressao)
			.containsExactly("voltas", "parar");
		// O substantivo ao lado do verbo continua nomeando a conversa: só o gerúndio sai.
		assertThat(extractor.extrair("todo mundo dando palpite", "POST"))
			.extracting(PapoAssuntoExtractor.Fala::expressao)
			.containsExactly("palpite");
	}

	@Test
	void gerundioNaoColaBigramaNemSobraNaLeitura() {
		// O verbo no gerúndio sai e o que sobra segue elegível: a conversa real não morre com ele.
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("estou crescendo a horta coletiva", "POST");

		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao).contains("horta", "coletiva");
	}

	@Test
	void siglaEPalavraCurtaLegitimaContinuamElegiveis() {
		// O conserto do ruído não pode matar a sigla legítima: "java", "sql" e "dev" são nomes
		// reais de conversa, mesmo curtos.
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("java sql dev na comunidade", "POST");

		// As siglas viram fala (e o cluster cola o par vizinho); nada sumiu pela régua.
		assertThat(falas).extracting(PapoAssuntoExtractor.Fala::expressao).contains("java", "sql", "dev");
		assertThat(extractor.assuntos(falas)).contains("java sql");
	}

	@Test
	void palavraGenericaDeProducaoNaoViraAssunto() {
		// O que a produção colocou em alta e não nomeia conversa: "cara", "dar", "pessoa".
		List<PapoAssuntoExtractor.Fala> falas = extractor.extrair("cara, dar valor a cada pessoa", "POST");

		assertThat(extractor.assuntos(falas)).doesNotContain("cara", "dar", "pessoa");
		assertThat(extractor.assuntoValido("cara")).isFalse();
		assertThat(extractor.assuntoValido("dar")).isFalse();
		assertThat(extractor.assuntoValido("pessoas")).isFalse();
	}

}
