package com.socialcoisa.rede.moderation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * O gatilho barato da barreira de conteúdo automatizado.
 *
 * <p>Dois lados, e os dois importam igual: texto que carrega o rastro do gerador precisa acender a
 * suspeita (senão a barreira nunca olha), e texto de gente comum — formal, técnico, com acento,
 * com lista curta — não pode acender (senão o custo do modelo explode e o post de gente honesta
 * entra na fila).</p>
 */
class AutomationSignalsTests {

	@Test
	void aTextThatDeclaresItselfMachineWrittenIsASignal() {
		assertThat(AutomationSignals.suspect("Como um modelo de linguagem, não tenho opiniões pessoais."))
			.isTrue();
		assertThat(AutomationSignals.suspect("I'm an AI, so I can't have preferences.")).isTrue();
	}

	@Test
	void assistantPostureIsASignal() {
		assertThat(AutomationSignals.suspect("Claro! Aqui está o texto que você pediu.")).isTrue();
		assertThat(AutomationSignals.suspect("Espero ter ajudado. Se precisar de mais alguma coisa...")).isTrue();
		assertThat(AutomationSignals.suspect("Hope this helps! Let me know if you need anything else.")).isTrue();
	}

	@Test
	void accentsAndCaseDoNotHideTheMarker() {
		assertThat(AutomationSignals.suspect("CLARO! AQUI ESTÁ a versão revisada")).isTrue();
		assertThat(AutomationSignals.suspect("Em resumo, os pontos principais são estes.")).isTrue();
	}

	@Test
	void generatorFormattingOnALongPostIsASignal() {
		String texto = """
			## Introdução

			**Primeiro ponto**
			- item um, com a descrição completa do que ele significa na prática
			- item dois, igualmente detalhado e sem voz pessoal nenhuma
			- item três, fechando a lista no mesmo tom impessoal de manual

			## Conclusão

			O texto acima cobre o assunto com a profundidade necessária para o leitor entender o contexto
			geral e os detalhes práticos de cada etapa mencionada anteriormente, sem nenhuma marca de quem
			escreveu: nem opinião, nem exemplo próprio, nem informalidade de conversa.""";

		assertThat(texto.length()).isGreaterThan(420);
		assertThat(AutomationSignals.suspect(texto)).isTrue();
	}

	@Test
	void aShortPostWithOneBulletIsNotStructure() {
		// Lista curta é como gente escreve. Sem o tamanho, isto viraria chamada de modelo.
		assertThat(AutomationSignals.suspect("**hoje**\n- café\n- praia")).isFalse();
	}

	@Test
	void ordinaryHumanTextIsNotASignal() {
		assertThat(AutomationSignals.suspect("acordei tarde hoje, perdi o ônibus e cheguei atrasado de novo"))
			.isFalse();
		assertThat(AutomationSignals.suspect("Alguém sabe se o evento de sábado vai ter transmissão?")).isFalse();
	}

	@Test
	void mentioningAiInAnOpinionIsNotASignal() {
		// A regra barra conta que publica saída de gerador, não quem fala sobre IA.
		assertThat(AutomationSignals.suspect("usei o chatgpt pra revisar o texto e achei o resultado morno"))
			.isFalse();
	}

	@Test
	void emptyContentIsNeverASignal() {
		assertThat(AutomationSignals.suspect(null)).isFalse();
		assertThat(AutomationSignals.suspect("   ")).isFalse();
	}
}
