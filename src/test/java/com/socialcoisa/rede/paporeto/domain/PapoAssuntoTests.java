package com.socialcoisa.rede.paporeto.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * A régua do assunto: a discriminação (a conversa contra a língua), o decaimento por fala, a
 * linha gravada e a compatibilidade de leitura. É aqui que mora a decisão de o que entra no
 * ranking — e o que é fala comum da rede fica de fora sem ninguém precisar listá-la à mão.
 */
class PapoAssuntoTests {

	private static final Instant AGORA = Instant.parse("2026-11-18T12:00:00Z");

	private static PapoAssunto assunto(String subject, long publicacoes, long publicacoesDoEscopo) {
		return new PapoAssunto(subject, 40, 0, 12, 3, AGORA, publicacoes, publicacoesDoEscopo, 20.0, 10.0);
	}

	@Test
	void decaimentoEhPorFalaEComMeiaVidaDeUmDia() {
		assertThat(PapoAssunto.decaimento(AGORA, AGORA)).isEqualTo(1.0);
		assertThat(PapoAssunto.decaimento(AGORA.minus(PapoAssunto.MEIA_VIDA), AGORA)).isEqualTo(0.5);
		assertThat(PapoAssunto.decaimento(AGORA.minus(PapoAssunto.MEIA_VIDA.multipliedBy(3)), AGORA)).isEqualTo(0.125);
		// Fala "do futuro" (relógio torto da fonte) não vale mais que a fala de agora.
		assertThat(PapoAssunto.decaimento(AGORA.plusSeconds(600), AGORA)).isEqualTo(1.0);
	}

	@Test
	void palavraDeUmaSoPalavraEmTodaParteNaoViraRanking() {
		PapoAssunto onipresente = assunto("vida", 60, 400);

		assertThat(onipresente.composta()).isFalse();
		assertThat(onipresente.onipresente()).isTrue();
		assertThat(onipresente.discriminacao()).isZero();
		assertThat(onipresente.pontuacao()).isZero();
	}

	@Test
	void expressaoCompostaOnipresenteFicaComOPiso() {
		// Conversa grande e legítima ("mercado de trabalho") não pode desaparecer por aparecer
		// em muita publicação: ela fica, com metade da potência.
		PapoAssunto composta = assunto("mercado de trabalho", 60, 400);

		assertThat(composta.composta()).isTrue();
		assertThat(composta.onipresente()).isFalse();
		assertThat(composta.discriminacao()).isEqualTo(PapoAssunto.PISO_ONIPRESENTE_COMPOSTO);
		assertThat(composta.pontuacao()).isEqualTo(10.0);
	}

	@Test
	void assuntoRaroValeMaisEOTetoSeguraARaridade() {
		// 8 de 400 publicações: raro de verdade, o bônus do logaritmo bate no teto.
		assertThat(assunto("pangolim", 8, 400).discriminacao()).isEqualTo(PapoAssunto.TETO_DISCRIMINACAO);
		// 50 de 400: aparece bastante, mas ainda é conversa — bônus pequeno, abaixo do teto.
		double doMeio = assunto("pessoa", 50, 400).discriminacao();
		assertThat(doMeio).isGreaterThan(1.0).isLessThan(PapoAssunto.TETO_DISCRIMINACAO);
		// A raridade é crescente: quanto menos publicações falam, mais o assunto vale.
		assertThat(assunto("pessoa", 20, 400).discriminacao())
			.isGreaterThan(assunto("pessoa", 50, 400).discriminacao());
	}

	@Test
	void semMassaDePublicacoesAReguaNaoJulga() {
		// Cinco publicações na janela: a fração é ruído e o assunto vale pelo que é — inclusive
		// a palavra solta (senão um cedo da manhã derrubaria o ranking inteiro).
		PapoAssunto palavra = assunto("vida", 5, 5);

		assertThat(palavra.onipresente()).isFalse();
		assertThat(palavra.discriminacao()).isEqualTo(1.0);
		assertThat(palavra.pontuacao()).isEqualTo(20.0);
	}

	@Test
	void linhaGravadaVoltaComOsMesmosNumeros() {
		PapoAssunto original = new PapoAssunto("mercado de trabalho", 40, 7, 12, 3, AGORA, 25, 400, 20.0, 10.0);

		PapoAssunto lido = PapoAssunto.de("mercado de trabalho", original.estadoComoJson());

		assertThat(lido.falasPost()).isEqualTo(40);
		assertThat(lido.falasComentario()).isEqualTo(7);
		assertThat(lido.pessoas()).isEqualTo(12);
		assertThat(lido.falasUltimaHora()).isEqualTo(3);
		assertThat(lido.ultimaFala()).isEqualTo(AGORA);
		assertThat(lido.publicacoes()).isEqualTo(25);
		assertThat(lido.janelaPublicacoes()).isEqualTo(400);
		assertThat(lido.potencia()).isCloseTo(20.0, within(0.000001));
		assertThat(lido.potenciaAntes()).isCloseTo(10.0, within(0.000001));
		assertThat(lido.legado()).isFalse();
	}

	@Test
	void linhaDaReguaAntigaEhLidaComoLegado() {
		String antiga = "{\"falasPost\":40,\"falasComentario\":7,\"pessoas\":12,\"falasUltimaHora\":3,"
				+ "\"ultimaFalaEpoch\":" + AGORA.getEpochSecond() + "}";

		PapoAssunto lido = PapoAssunto.de("meetup", antiga);

		assertThat(lido.legado()).isTrue();
		assertThat(lido.potencia()).isZero();
		// A régua antiga ainda pontua a linha velha, com o decaimento único da última fala.
		assertThat(lido.potenciaLegada(0.30, 0.25, 0.25, 0.20, AGORA))
			.isCloseTo(40 * 0.30 + 7 * 0.25 + Math.sqrt(12) * 0.25 + Math.sqrt(3) * 0.20, within(0.000001));
	}

	@Test
	void expressaoComVariasPalavrasEhComposta() {
		assertThat(assunto("java", 1, 400).composta()).isFalse();
		assertThat(assunto("mercado de trabalho", 1, 400).composta()).isTrue();
	}

}
