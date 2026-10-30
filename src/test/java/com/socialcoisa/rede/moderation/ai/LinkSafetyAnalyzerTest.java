package com.socialcoisa.rede.moderation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.moderation.domain.LinkSafetyVerdict;
import com.socialcoisa.rede.moderation.domain.PageSummary;

import tools.jackson.databind.ObjectMapper;

class LinkSafetyAnalyzerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private LinkSafetyAnalyzer analyzer(String apiKey) {
		ModerationAiService ai = new ModerationAiService(null, (tipo, id) -> Optional.empty(),
			(tipo, id, limite) -> java.util.List.of(), "https://provedor.invalido/v1", apiKey,
			"modelo-teste", objectMapper);
		return new LinkSafetyAnalyzer(ai, objectMapper);
	}

	@Test
	void leOVereditoExplicito() {
		var veredito = analyzer("chave").parse(
			"{\"veredito\":\"EXPLICIT\",\"gravidade\":\"HIGH\",\"confianca\":0.93,\"motivo\":\"video adulto\"}");

		assertThat(veredito).isPresent();
		assertThat(veredito.get().verdict()).isEqualTo(LinkSafetyVerdict.EXPLICIT);
		assertThat(veredito.get().confidence()).isEqualTo(0.93);
	}

	@Test
	void leDestinoSuspeito() {
		var veredito = analyzer("chave").parse(
			"{\"veredito\":\"SUSPICIOUS\",\"gravidade\":\"MEDIUM\",\"confianca\":0.8,\"motivo\":\"phishing\"}");

		assertThat(veredito).isPresent();
		assertThat(veredito.get().verdict()).isEqualTo(LinkSafetyVerdict.SUSPICIOUS);
	}

	@Test
	void vereditoForaDoContratoNaoVale() {
		assertThat(analyzer("chave").parse("{\"veredito\":\"TALVEZ\",\"confianca\":0.9}")).isEmpty();
		assertThat(analyzer("chave").parse("{\"veredito\":\"SAFE\",\"confianca\":2.0}")).isEmpty();
		assertThat(analyzer("chave").parse("nao e json")).isEmpty();
		assertThat(analyzer("chave").parse("")).isEmpty();
		assertThat(analyzer("chave").parse(null)).isEmpty();
	}

	@Test
	void semChaveNaoHaPalpiteNemChamada() {
		PageSummary pagina = new PageSummary("https://exemplo.com/x", "Titulo", "Descricao");
		assertThat(analyzer("").analyze(pagina)).isEmpty();
	}

	@Test
	void paginaSemResumoNaoEhAnalisada() {
		assertThat(analyzer("chave").analyze(null)).isEmpty();
		assertThat(analyzer("chave").analyze(new PageSummary("https://exemplo.com/x", " ", ""))).isEmpty();
	}

	@Test
	void soAgeSozinhoComViolacaoConfirmada() {
		assertThat(LinkSafetyAnalyzer.canActAlone(
			new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.EXPLICIT, "HIGH", 0.9, ""))).isTrue();
		assertThat(LinkSafetyAnalyzer.canActAlone(
			new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.SUSPICIOUS, "HIGH", 0.75, ""))).isTrue();
		// Abaixo do piso: revisão humana.
		assertThat(LinkSafetyAnalyzer.canActAlone(
			new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.EXPLICIT, "HIGH", 0.5, ""))).isFalse();
		// Destino limpo ou incerto nunca é barrado sozinho.
		assertThat(LinkSafetyAnalyzer.canActAlone(
			new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.SAFE, "LOW", 0.99, ""))).isFalse();
		assertThat(LinkSafetyAnalyzer.canActAlone(
			new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.UNSURE, "LOW", 0.99, ""))).isFalse();
		assertThat(LinkSafetyAnalyzer.canActAlone(null)).isFalse();
	}

	@Test
	void oResumoDaPaginaEntraComoDadoNaoConfiavel() {
		PageSummary pagina = new PageSummary(URI.create("https://exemplo.com/x").toString(), "Meu video",
			"descricao qualquer");

		String prompt = LinkSafetyAnalyzer.userPrompt(pagina);

		assertThat(prompt).contains("dado nao confiavel");
		assertThat(prompt).contains("Meu video");
		assertThat(prompt).contains("https://exemplo.com/x");
	}

}
