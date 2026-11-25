package com.socialcoisa.rede.moderation.application;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;

/**
 * O trecho do conteúdo denunciado que a notificação de punição mostra como prova. É lido na
 * decisão, antes da remoção — depois não existe mais de onde tirar.
 *
 * <p>A leitura do conteúdo é a mesma que a revisão do painel usa, então cobre todos os alvos com
 * texto. O motivo da denúncia vira a justificativa em português, porque a notificação vai para a
 * conta punida, não para o time.</p>
 */
@Component
public class ProvaDaPunicao {

	private final ReportedContentReader conteudo;

	ProvaDaPunicao(ReportedContentReader conteudo) {
		this.conteudo = conteudo;
	}

	/**
	 * Trecho do conteúdo denunciado, ou vazio quando não há o que citar. O leitor devolve o texto
	 * inteiro do alvo (até 4.000 caracteres para a triagem); aqui cabe só o que a notificação cita.
	 */
	public Optional<String> trechoDe(Report report) {
		return conteudo.contentOf(report).map(ProvaDaPunicao::corte);
	}

	private static String corte(String texto) {
		String limpo = texto.replaceAll("\\s+", " ").trim();
		return limpo.isEmpty() ? null
			: limpo.length() > MAX_TRECHO ? limpo.substring(0, MAX_TRECHO).trim() + "…" : limpo;
	}

	/** Cabe no body da notificacao (2.000) junto da abertura e da justificativa. */
	static final int MAX_TRECHO = 140;

	/** Para que serve a punição, em português — o texto que a conta punida lê. */
	public static String justificativaPublica(ReportReason reason) {
		return switch (reason) {
			case AI_GENERATED -> "o conteúdo foi identificado como gerado por IA e apresentado como se fosse seu";
			case FAKE_CONTENT -> "o conteúdo foi considerado falso";
			case SCAM -> "o conteúdo foi considerado golpe";
			case DISCRIMINATION -> "o conteúdo foi considerado discriminação";
			case PREJUDICE -> "o conteúdo foi considerado preconceito";
			case HARASSMENT -> "o conteúdo foi considerado assédio";
			case SPAM -> "o conteúdo foi considerado spam";
			case VIOLENCE -> "o conteúdo foi considerado violência";
			case SEXUAL_CONTENT -> "o conteúdo foi considerado nudez ou ato sexual explícito";
			case COPYRIGHT -> "o conteúdo usa material protegido por direitos autorais";
			case OTHER -> "o conteúdo foi considerado fora das regras de uso";
		};
	}
}