package com.socialcoisa.rede.moderation.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Classifica UMA imagem como segura, explícita ou conteúdo sexual com menor.
 *
 * <p>Separado do {@link ModerationAiService} de propósito: lá o modelo julga uma DENÚNCIA (há um
 * denunciante, uma categoria e um alvo), aqui ele só responde o que vê. Sem essa separação, o
 * veredito de imagem herdaria o formato de denúncia e a decisão automática ficaria dependente de
 * alguém ter denunciado — que é exatamente a lacuna que esta barreira fecha.</p>
 *
 * <p>Prompt próprio, contrato próprio, e a mesma regra de sempre: o modelo só dá um palpite
 * estruturado; quem apaga conteúdo e aplica sanção é o backend, e a conta sempre pode recorrer.</p>
 */
@Service
public class MediaSafetyAnalyzer {

	/** Abaixo disto a imagem não é barrada sozinha: vira revisão humana. */
	public static final double MIN_CONFIDENCE = 0.7;

	private final ModerationAiService ai;
	private final ObjectMapper objectMapper;

	public MediaSafetyAnalyzer(ModerationAiService ai, ObjectMapper objectMapper) {
		this.ai = ai;
		this.objectMapper = objectMapper;
	}

	/** Modelo em uso, gravado no ledger junto do veredito. */
	public String modelName() {
		return ai.model();
	}

	/**
	 * Instrução do modelo. Fica `static` porque é texto puro e assim dá para testar o que ele recebe
	 * sem subir contexto de Spring.
	 */
	static String systemPrompt() {
		return """
			Voce classifica UMA imagem enviada por uma pessoa numa rede social profissional.
			Responda SOMENTE um objeto JSON valido, sem texto fora dele:
			{"veredito":"SAFE|EXPLICIT|SEXUAL_MINOR|UNSURE","gravidade":"LOW|MEDIUM|HIGH|CRITICAL",
			"confianca":0.0-1.0,"motivo":"..."}

			Definicoes:
			- SAFE: sem nudez e sem ato sexual. Inclui pessoa vestida, roupa de banho, praia, arte,
			  bebe amamentando, exame medico, materia jornalistica e educacao sexual sem exposicao
			  explicita.
			- EXPLICIT: nudez com genitalia, anus ou mama feminina expostos; ato sexual; orgao sexual
			  em destaque; pornografia. Vale para foto, desenho, anime e hentai.
			- SEXUAL_MINOR: qualquer conteudo sexual, sugerido ou explicito, envolvendo pessoa que
			  aparenta ser menor de idade, real ou ficticia (inclusive desenho). E a violacao mais
			  grave e deve ser marcada mesmo em duvida razoavel.
			- UNSURE: imagem que voce nao consegue avaliar (desfocada, escura, cortada, ambigua).

			Regras obrigatorias:
			- "confianca" e sobre a SUA certeza do veredito, nao sobre a gravidade.
			- Na duvida entre SAFE e EXPLICIT, use UNSURE — nunca EXPLICIT por suposicao.
			- Na duvida entre EXPLICIT e SEXUAL_MINOR, use SEXUAL_MINOR.
			- "motivo" descreve o que esta VISIVEL na imagem, em uma frase, sem termos graficos
			  desnecessarios e sem reproduzir detalhes que nao mudam a classificacao.
			- Nao invente o que nao esta na imagem.
			""";
	}

	static String userPrompt() {
		return "Classifique a imagem anexada conforme as definicoes e devolva SOMENTE o JSON.";
	}

	/**
	 * Analisa os bytes de uma imagem (já reduzida pelo leitor). Vazio = sem palpite válido, e a
	 * mídia fica sem decisão automática.
	 */
	public Optional<Verdict> analyze(byte[] image) {
		if (image == null || image.length == 0 || !ai.available()) {
			return Optional.empty();
		}
		String raw = ai.triageVision(systemPrompt(), userPrompt(), List.of(image)).orElse(null);
		return parse(raw);
	}

	/** Extrai e valida o JSON. Qualquer coisa fora do contrato invalida o palpite (fail-safe). */
	public Optional<Verdict> parse(String modelOutput) {
		if (!StringUtils.hasText(modelOutput)) {
			return Optional.empty();
		}
		try {
			JsonNode node = objectMapper.readTree(modelOutput);
			MediaSafetyVerdict verdict = switch (node.path("veredito").asString()) {
				case "SAFE" -> MediaSafetyVerdict.SAFE;
				case "EXPLICIT" -> MediaSafetyVerdict.EXPLICIT;
				case "SEXUAL_MINOR" -> MediaSafetyVerdict.SEXUAL_MINOR;
				case "UNSURE" -> MediaSafetyVerdict.UNSURE;
				default -> null;
			};
			if (verdict == null) {
				return Optional.empty();
			}
			double confidence = node.path("confianca").asDouble(-1);
			if (confidence < 0 || confidence > 1) {
				return Optional.empty();
			}
			String severity = node.path("gravidade").asString();
			if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity)) {
				severity = "LOW";
			}
			return Optional.of(new Verdict(verdict, severity, confidence, node.path("motivo").asString()));
		}
		catch (Exception invalid) {
			return Optional.empty();
		}
	}

	/**
	 * A imagem pode ser barrada sem uma pessoa olhar? Exige violação E confiança acima do piso.
	 * Conteúdo sexual com menor dispensa o piso: a dúvida razoável já basta para tirar do ar.
	 */
	public static boolean canActAlone(Verdict verdict) {
		if (verdict == null || !verdict.verdict().isViolation()) {
			return false;
		}
		return verdict.verdict() == MediaSafetyVerdict.SEXUAL_MINOR || verdict.confidence() >= MIN_CONFIDENCE;
	}

	public record Verdict(MediaSafetyVerdict verdict, String severity, double confidence, String reason) {
	}
}
