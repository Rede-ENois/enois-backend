package com.socialcoisa.rede.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.moderation.domain.ContentAutomationVerdict;

import tools.jackson.databind.ObjectMapper;

/**
 * O contrato do classificador de conteúdo automatizado e o único gate que autoriza a automação a
 * tirar um post do ar sozinha: automatizado confirmado e confiança acima do piso.
 */
class ContentAutomationAnalyzerTests {

	private ModerationAiService ai;
	private ContentAutomationAnalyzer analyzer;

	@BeforeEach
	void preparar() {
		ai = mock(ModerationAiService.class);
		analyzer = new ContentAutomationAnalyzer(ai, new ObjectMapper());
	}

	@Test
	void aWellFormedAnswerIsParsed() {
		var veredito = analyzer.parse("{\"veredito\":\"AUTOMATED\",\"confianca\":0.93,\"motivo\":\"postura de assistente\"}");

		assertThat(veredito).isPresent();
		assertThat(veredito.get().verdict()).isEqualTo(ContentAutomationVerdict.AUTOMATED);
		assertThat(veredito.get().confidence()).isEqualTo(0.93);
		assertThat(veredito.get().reason()).isEqualTo("postura de assistente");
	}

	@Test
	void anAnswerOutsideTheContractIsDiscarded() {
		// Fail-safe: veredito desconhecido, confiança fora da faixa e lixo não viram palpite.
		assertThat(analyzer.parse("{\"veredito\":\"TALVEZ\",\"confianca\":0.9}")).isEmpty();
		assertThat(analyzer.parse("{\"veredito\":\"HUMAN\",\"confianca\":7}")).isEmpty();
		assertThat(analyzer.parse("{\"veredito\":\"HUMAN\",\"confianca\":-1}")).isEmpty();
		assertThat(analyzer.parse("{\"motivo\":\"sem veredito\"}")).isEmpty();
		assertThat(analyzer.parse("nao e json")).isEmpty();
		assertThat(analyzer.parse(null)).isEmpty();
	}

	@Test
	void onlyAutomatedWithEnoughConfidenceCanHideAPost() {
		assertThat(ContentAutomationAnalyzer.canQuarantine(
			new ContentAutomationAnalyzer.Verdict(ContentAutomationVerdict.AUTOMATED, 0.9, "x"))).isTrue();
		assertThat(ContentAutomationAnalyzer.canQuarantine(
			new ContentAutomationAnalyzer.Verdict(ContentAutomationVerdict.AUTOMATED, 0.79, "x"))).isFalse();
		assertThat(ContentAutomationAnalyzer.canQuarantine(
			new ContentAutomationAnalyzer.Verdict(ContentAutomationVerdict.UNSURE, 0.99, "x"))).isFalse();
		assertThat(ContentAutomationAnalyzer.canQuarantine(
			new ContentAutomationAnalyzer.Verdict(ContentAutomationVerdict.HUMAN, 0.99, "x"))).isFalse();
		assertThat(ContentAutomationAnalyzer.canQuarantine(null)).isFalse();
	}

	@Test
	void withoutTheModelThereIsNoVerdict() {
		when(ai.available()).thenReturn(false);

		assertThat(analyzer.analyze("Claro! Aqui está o texto que você pediu.")).isEmpty();
		verify(ai, never()).triageVision(anyString(), anyString(), anyList());
	}

	@Test
	void theModelIsAskedWithItsOwnPromptAndThePostAsUntrustedData() {
		when(ai.available()).thenReturn(true);
		when(ai.triageVision(anyString(), anyString(), any())).thenReturn(
			Optional.of("{\"veredito\":\"AUTOMATED\",\"confianca\":0.91,\"motivo\":\"postura de assistente\"}"));

		var veredito = analyzer.analyze("Claro! Aqui está o texto que você pediu.");

		assertThat(veredito).isPresent();
		assertThat(veredito.get().verdict()).isEqualTo(ContentAutomationVerdict.AUTOMATED);
		verify(ai).triageVision(
			org.mockito.ArgumentMatchers.contains("HUMAN|AUTOMATED|UNSURE"),
			org.mockito.ArgumentMatchers.contains("dado nao confiavel"),
			org.mockito.ArgumentMatchers.eq(List.of()));
	}
}