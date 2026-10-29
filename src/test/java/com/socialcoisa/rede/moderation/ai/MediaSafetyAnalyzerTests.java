package com.socialcoisa.rede.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedMediaReader;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;

/**
 * O contrato do classificador de imagem: o que ele aceita como palpite, o que invalida, e quando a
 * automação pode agir sem uma pessoa olhar.
 */
class MediaSafetyAnalyzerTests {

	private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

	private final MediaSafetyAnalyzer analyzer = new MediaSafetyAnalyzer(servicoSemChave(), MAPPER);

	private static ModerationAiService servicoSemChave() {
		ReportedContentReader semTexto = (targetType, targetId) -> Optional.empty();
		ReportedMediaReader semMidia = (targetType, targetId, limit) -> List.of();
		return new ModerationAiService(null, semTexto, semMidia, "https://provedor.invalido/v1", "",
			"modelo-de-teste", MAPPER);
	}

	@Test
	void explicitWithEnoughConfidenceCanActAlone() {
		var veredito = analyzer.parse(
			"{\"veredito\":\"EXPLICIT\",\"gravidade\":\"CRITICAL\",\"confianca\":0.93,\"motivo\":\"nudez visivel\"}");

		assertThat(veredito).isPresent();
		assertThat(veredito.get().verdict()).isEqualTo(MediaSafetyVerdict.EXPLICIT);
		assertThat(MediaSafetyAnalyzer.canActAlone(veredito.get())).isTrue();
	}

	@Test
	void explicitBelowTheConfidenceFloorGoesToHumanReview() {
		// Barreira automática que erra derruba conta de gente honesta: abaixo do piso, não age.
		var veredito = analyzer.parse(
			"{\"veredito\":\"EXPLICIT\",\"gravidade\":\"HIGH\",\"confianca\":0.55,\"motivo\":\"talvez\"}");

		assertThat(veredito).isPresent();
		assertThat(MediaSafetyAnalyzer.canActAlone(veredito.get())).isFalse();
	}

	@Test
	void sexualContentWithMinorActsEvenWithoutTheConfidenceFloor() {
		// A violação mais grave da lista: dúvida razoável já basta para sair do ar.
		var veredito = analyzer.parse(
			"{\"veredito\":\"SEXUAL_MINOR\",\"gravidade\":\"CRITICAL\",\"confianca\":0.4,\"motivo\":\"aparencia\"}");

		assertThat(veredito).isPresent();
		assertThat(MediaSafetyAnalyzer.canActAlone(veredito.get())).isTrue();
	}

	@Test
	void safeAndUnsureNeverActAlone() {
		var seguro = analyzer.parse(
			"{\"veredito\":\"SAFE\",\"gravidade\":\"LOW\",\"confianca\":0.99,\"motivo\":\"praia\"}");
		var incerto = analyzer.parse(
			"{\"veredito\":\"UNSURE\",\"gravidade\":\"LOW\",\"confianca\":0.99,\"motivo\":\"desfocada\"}");

		assertThat(MediaSafetyAnalyzer.canActAlone(seguro.get())).isFalse();
		assertThat(MediaSafetyAnalyzer.canActAlone(incerto.get())).isFalse();
	}

	@Test
	void aVerdictOutsideTheContractIsDiscarded() {
		// Veredito inventado, confiança fora da faixa e JSON quebrado: nada passa (fail-safe).
		assertThat(analyzer.parse("{\"veredito\":\"GORE\",\"confianca\":0.9}")).isEmpty();
		assertThat(analyzer.parse("{\"veredito\":\"EXPLICIT\",\"confianca\":7}")).isEmpty();
		assertThat(analyzer.parse("{\"veredito\":\"EXPLICIT\"")).isEmpty();
		assertThat(analyzer.parse("")).isEmpty();
		assertThat(analyzer.parse(null)).isEmpty();
	}

	@Test
	void withoutAnImageThereIsNoGuessEvenWithAKey() {
		// Sem bytes não há o que olhar: o serviço não inventa veredito.
		assertThat(analyzer.analyze(new byte[0])).isEmpty();
		assertThat(analyzer.analyze(null)).isEmpty();
	}

	@Test
	void theInstructionNamesSexualContentAndForbidsGuessing() {
		String prompt = MediaSafetyAnalyzer.systemPrompt();

		assertThat(prompt).contains("EXPLICIT").contains("SEXUAL_MINOR").contains("nudez");
		// A regra que faltava: não marcar EXPLICIT por suposição.
		assertThat(prompt).contains("nunca EXPLICIT por suposicao");
	}
}
