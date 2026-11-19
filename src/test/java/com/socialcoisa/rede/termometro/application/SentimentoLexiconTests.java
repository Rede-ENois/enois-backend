package com.socialcoisa.rede.termometro.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.termometro.domain.Emocao;

/**
 * O léxico do Termômetro, termo a termo: a classificação é DETERMINÍSTICA — dobragem sem
 * acento, negação pelas duas palavras anteriores, especificidade antes de generalidade e
 * prioridade no empate (precisando > pesada > cansada > celebrando > curiosa > alegre).
 * O que este teste pina é o contrato que o painel herda: mesma frase, mesma caixa, sempre.
 */
class SentimentoLexiconTests {

	private final SentimentoLexicon lexicon = new SentimentoLexicon();

	@Test
	void dobraCaixaEAcentoParaCasar() {
		// "não aguento mais" e "NAO AGUENTO MAIS" caem na mesma caixa.
		assertThat(lexicon.classificar("não aguento mais isso"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.PRECISANDO));
		assertThat(lexicon.classificar("NAO AGUENTO MAIS ISSO"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.PRECISANDO));
		// "dúvida" sem acento vira "duvida": mesma caixa.
		assertThat(lexicon.classificar("tenho uma duvida sobre o teste"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.CURIOSA));
		assertThat(lexicon.classificar("tenho uma DÚVIDA sobre o teste"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.CURIOSA));
	}

	@Test
	void textoSemTermoClassificavelFicaNeutro() {
		// A maioria do feed é neutra: link, meme, "bom dia" sem mais nada.
		assertThat(lexicon.classificar("bom dia grupo")).isEqualTo(new SentimentoLexicon.Neutro());
		assertThat(lexicon.classificar("")).isEqualTo(new SentimentoLexicon.Neutro());
		assertThat(lexicon.classificar(null)).isEqualTo(new SentimentoLexicon.Neutro());
		assertThat(lexicon.classificar("   ")).isEqualTo(new SentimentoLexicon.Neutro());
	}

	@Test
	void negacaoInverteOCaixa() {
		// "não tenho raiva" NÃO é PESADA: a negação (duas palavras anteriores) desclassifica.
		assertThat(lexicon.classificar("hoje não tenho raiva de nada"))
			.isNotEqualTo(new SentimentoLexicon.Emocional(Emocao.PESADA));
		// Fora da janela de duas palavras a negação não alcança: é o contrato documentado.
		assertThat(lexicon.classificar("não digo que sinto raiva"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.PESADA));
		// "não consegui" fica sob negação: o termo não conta como festa.
		assertThat(lexicon.classificar("não consegui"))
			.isNotEqualTo(new SentimentoLexicon.Emocional(Emocao.CELEBRANDO));
		// Sem negação: casamento direto.
		assertThat(lexicon.classificar("consegui o emprego"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.CELEBRANDO));
	}

	@Test
	void maisTermosVenceEmpateVaiParaPrioridade() {
		// Pesada vence Alegre por contagem: 2 termos pesados contra 1 alegre.
		assertThat(lexicon.classificar("que raiva dessa discussão, mas que legal o café"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.PESADA));
		// Empate vai para a prioridade: "consegui" (CELEBRANDO) + "feliz" (ALEGRE) = 1 a 1,
		// e celebrando vem antes de alegre na ordem declarada.
		assertThat(lexicon.classificar("consegui e estou feliz"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.CELEBRANDO));
		// Precisando é a prioridade máxima: pedido de ajuda não perde empate.
		assertThat(lexicon.classificar("to mal, mas obrigada pela dica"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.PRECISANDO));
	}

	@Test
	void especificidadeAntesDeGeneralidade() {
		// "não aguento mais" (PRECISANDO) é mais específico que "não aguento" (CANSADA): as duas
		// casam, e a contagem é 1 a 1 — a prioridade desempata para PRECISANDO.
		assertThat(lexicon.classificar("não aguento mais"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.PRECISANDO));
		// Sem o "mais", só o termo de CANSADA casa.
		assertThat(lexicon.classificar("não aguento segunda-feira"))
			.isEqualTo(new SentimentoLexicon.Emocional(Emocao.CANSADA));
	}

	@Test
	void dobrarEaFuncaoCompartilhadaDoCasamento() {
		// A dobragem é o passo compartilhado: minúsculas, sem acento.
		assertThat(SentimentoLexicon.dobrar("DÚVIDA")).isEqualTo("duvida");
		assertThat(SentimentoLexicon.dobrar("Parabéns!")).isEqualTo("parabens!");
	}

}
