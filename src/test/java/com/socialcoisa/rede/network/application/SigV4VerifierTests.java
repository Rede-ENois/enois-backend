package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * SigV4 (brief §11): o verificador tem de concordar com o <b>vetor oficial da AWS</b>, não com a
 * minha leitura da especificação. Os testes abaixo usam o exemplo publicado no material de
 * "Examples of the complete Signature Version 4 signing process" (`GET /test.txt`, serviço `service`,
 * região `us-east-1`) e o caso do cabeçalho de payload.
 *
 * <p>Um teste que só confirmasse "assinei comigo mesmo e confere" passaria com a canonicalização
 * errada no mesmo jeito dos dois lados — é o motivo de o vetor externo estar aqui.</p>
 */
class SigV4VerifierTests {

	/** Vetor oficial AWS: segredo, data, região e serviço do exemplo. */
	private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
	private static final String AMZ_DATE = "20150830T123600Z";

	/** Payload do vetor: o hash SHA-256 da string vazia, porque o corpo é vazio. */
	private static final String EMPTY_PAYLOAD = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	/**
	 * Vetor oficial `get-vanilla` do repositório {@code awslabs/aws-c-auth}
	 * (`tests/aws-signing-test-suite/v4/get-vanilla`): `GET /`, só {@code host} e {@code x-amz-date}.
	 *
	 * <p>A assinatura publicada é
	 * {@code 5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31}.</p>
	 */
	@Test
	void confere_o_vetor_oficial_get_vanilla() {
		var headers = Map.of(
			"host", "example.amazonaws.com",
			"x-amz-date", AMZ_DATE);
		var request = new SigV4Verifier.Request("GET", "/", Map.of(), headers, EMPTY_PAYLOAD);
		var parsed = new SigV4Verifier.Parsed("AKIDEXAMPLE", "us-east-1", "service",
			"host;x-amz-date", "");

		String signature = SigV4Verifier.signature(request, parsed, SECRET, "20150830");

		assertThat(signature).isEqualTo("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
	}

	/**
	 * Exemplo 2 (`get-vanilla-query-order-key-case`): a consulta é ordenada por nome, independente da
	 * ordem em que o cliente a mandou — senão dois SDKs com a mesma requisição lógica assinariam
	 * diferente.
	 *
	 * <p>Assinatura publicada:
	 * {@code b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500}.</p>
	 */
	@Test
	void confere_o_vetor_oficial_com_consulta_ordenada() {
		var headers = Map.of(
			"host", "example.amazonaws.com",
			"x-amz-date", AMZ_DATE);
		// Ordem de chegada invertida de propósito: Param2 antes de Param1.
		var query = new java.util.LinkedHashMap<String, String>();
		query.put("Param2", "value2");
		query.put("Param1", "value1");
		var request = new SigV4Verifier.Request("GET", "/", query, headers, EMPTY_PAYLOAD);
		var parsed = new SigV4Verifier.Parsed("AKIDEXAMPLE", "us-east-1", "service",
			"host;x-amz-date", "");

		assertThat(SigV4Verifier.signature(request, parsed, SECRET, "20150830"))
			.isEqualTo("b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500");
	}

	/** O header {@code Authorization} é lido no formato que o SDK manda. */
	@Test
	void le_o_header_authorization_no_formato_do_sdk() {
		var parsed = SigV4Verifier.parseAuthorization("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/"
			+ "us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
			+ "Signature=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

		assertThat(parsed).isNotNull();
		assertThat(parsed.accessKey()).isEqualTo("AKIDEXAMPLE");
		assertThat(parsed.region()).isEqualTo("us-east-1");
		assertThat(parsed.service()).isEqualTo("s3");
		assertThat(parsed.signedHeaders()).isEqualTo("host;x-amz-content-sha256;x-amz-date");
	}

	/** Esquema errado ou credencial sem `aws4_request` não é SigV4: quem chama decide o que fazer. */
	@Test
	void authorization_de_outro_esquema_nao_e_sigv4() {
		assertThat(SigV4Verifier.parseAuthorization(null)).isNull();
		assertThat(SigV4Verifier.parseAuthorization("Bearer abc")).isNull();
		// Escopo com 4 partes (sem aws4_request) não é SigV4 válido.
		assertThat(SigV4Verifier.parseAuthorization("AWS4-HMAC-SHA256 Credential=AK/20150830/us-east-1/s3, "
			+ "SignedHeaders=host, Signature=" + "0".repeat(64))).isNull();
	}

	/** Não assina com segredo vazio: credencial sem segredo é recusada, não "aceita por omissão". */
	@Test
	void segredo_ausente_nunca_confere() {
		var headers = Map.of("host", "example.amazonaws.com", "x-amz-date", AMZ_DATE);
		var request = new SigV4Verifier.Request("GET", "/", Map.of(), headers, EMPTY_PAYLOAD);
		var parsed = new SigV4Verifier.Parsed("AKIDEXAMPLE", "us-east-1", "service", "host;x-amz-date",
			"5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");

		assertThat(SigV4Verifier.verify(request, parsed, "", Instant.parse("2015-08-30T12:36:00Z"))).isFalse();
		assertThat(SigV4Verifier.verify(request, parsed, null, Instant.parse("2015-08-30T12:36:00Z"))).isFalse();
	}

	/** O caso feliz de verdade: o vetor oficial conferindo pelo `verify`. */
	@Test
	void verify_aceita_o_vetor_oficial_e_recusa_assinatura_adulterada() {
		var headers = Map.of("host", "example.amazonaws.com", "x-amz-date", AMZ_DATE);
		var request = new SigV4Verifier.Request("GET", "/", Map.of(), headers, EMPTY_PAYLOAD);
		var parsed = new SigV4Verifier.Parsed("AKIDEXAMPLE", "us-east-1", "service", "host;x-amz-date",
			"5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
		Instant now = Instant.parse("2015-08-30T12:36:00Z");

		assertThat(SigV4Verifier.verify(request, parsed, SECRET, now)).isTrue();

		// Um dígito trocado derruba: a comparação é da assinatura inteira, não de prefixo.
		var adulterada = new SigV4Verifier.Parsed("AKIDEXAMPLE", "us-east-1", "service", "host;x-amz-date",
			"4fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
		assertThat(SigV4Verifier.verify(request, adulterada, SECRET, now)).isFalse();
	}

	/**
	 * Relógio adiantado/atrasado além da tolerância é recusado. Sem isso, uma assinatura capturada
	 * valeria para sempre — é o replay que o {@code x-amz-date} existe para cortar.
	 */
	@Test
	void data_fora_da_janela_e_recusada() {
		var headers = Map.of("host", "example.amazonaws.com", "x-amz-date", AMZ_DATE);
		var request = new SigV4Verifier.Request("GET", "/", Map.of(), headers, EMPTY_PAYLOAD);
		var parsed = new SigV4Verifier.Parsed("AKIDEXAMPLE", "us-east-1", "service", "host;x-amz-date",
			"5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");

		// A data assinada é 12:36:00 (o vetor). "Agora" é comparado a ela, não a 12:36:30.
		Instant assinado = Instant.parse("2015-08-30T12:36:00Z");
		Instant muitoDepois = assinado.plusSeconds(SigV4Verifier.CLOCK_SKEW_SECONDS + 1);
		Instant muitoAntes = assinado.minusSeconds(SigV4Verifier.CLOCK_SKEW_SECONDS + 1);

		assertThat(SigV4Verifier.verify(request, parsed, SECRET, muitoDepois)).isFalse();
		assertThat(SigV4Verifier.verify(request, parsed, SECRET, muitoAntes)).isFalse();
		// Na borda exata ainda vale: recusar cedo demais quebra cliente com relógio correto.
		assertThat(SigV4Verifier.verify(request, parsed, SECRET,
			assinado.plusSeconds(SigV4Verifier.CLOCK_SKEW_SECONDS))).isTrue();
		// E o "agora" do vetor, exato, vale.
		assertThat(SigV4Verifier.verify(request, parsed, SECRET, assinado)).isTrue();
	}

	/**
	 * Caminho com caractere que precisa de codificação: o valor enviado na URL (já decodificado pelo
	 * servidor) tem de voltar a codificar igual, senão todo objeto com espaço no nome falharia.
	 */
	@Test
	void caminho_canonico_codifica_espaco_e_preserva_barra() {
		assertThat(SigV4Verifier.canonicalUri("/bucket/a b/c.jpg")).isEqualTo("/bucket/a%20b/c.jpg");
		assertThat(SigV4Verifier.canonicalUri("/bucket/a+b")).isEqualTo("/bucket/a%2Bb");
		// "~" e "." passam; "/" separa segmento.
		assertThat(SigV4Verifier.canonicalUri("/b/x~y.z")).isEqualTo("/b/x~y.z");
		assertThat(SigV4Verifier.canonicalUri("/")).isEqualTo("/");
	}

	/** A consulta codifica chave e valor e ordena; espaço é %20, nunca '+'. */
	@Test
	void consulta_canonica_codifica_e_ordena() {
		var query = new java.util.LinkedHashMap<String, String>();
		query.put("prefix", "a b");
		query.put("list-type", "2");
		assertThat(SigV4Verifier.canonicalQuery(query)).isEqualTo("list-type=2&prefix=a%20b");
	}

	/** Cabeçalho: aparar e colapsar espaço interno — o cliente assina o valor normalizado. */
	@Test
	void valor_de_cabecalho_normaliza_espaco() {
		assertThat(SigV4Verifier.normalizeValue("  a   b ")).isEqualTo("a b");
		assertThat(SigV4Verifier.normalizeValue(null)).isEmpty();
	}
}