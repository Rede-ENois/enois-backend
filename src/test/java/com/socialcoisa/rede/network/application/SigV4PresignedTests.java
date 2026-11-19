package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * URLs <b>pré-assinadas</b> (brief §11): a assinatura vem na <i>query</i>, não no cabeçalho.
 *
 * <p>O que este teste tem de provar, e o round-trip sozinho não prova:</p>
 * <ul>
 *   <li>a <b>expiração</b> existe e é diferente do relógio do servidor — uma URL de 900 s tem de
 *   morrer em 900 s, e não em 15 min de tolerância;</li>
 *   <li>parâmetro a mais na URL <b>quebra</b> a assinatura — senão alguém acrescentaria
 *   {@code response-content-disposition} e mudaria o comportamento sem assinar;</li>
 *   <li>{@code X-Amz-Signature} <b>não</b> entra na consulta canônica, que é a regra que separa o
 *   que se assina de quem assina;</li>
 *   <li>segredo errado e {@code X-Amz-Expires} absurdo são recusados.</li>
 * </ul>
 *
 * <p><b>A referência é o próprio verificador.</b> Não há vetor oficial da AWS para presigned aqui, e
 * inventar um com o gerador escrito à mão testaria a minha cópia da regra contra ela mesma. O que se
 * confere é que a requisição <em>reconstruída a partir da URL, como um cliente faria</em> verifica — e
 * que cada desvio dela não verifica. A comparação contra um SDK real é o teste de integração do
 * gateway.</p>
 */
class SigV4PresignedTests {

	private static final String SECRET = "segredo-presigned-de-teste";
	private static final String ACCESS_KEY = "AKIAENOISPRESIGN";
	private static final String AMZ_DATE = "20261118T120000Z";
	private static final String DATE_STAMP = "20261118";

	@Test
	void url_reconstruida_de_uma_query_verifica_ate_expirar() {
		String query = queryCompleta("900");
		SigV4Verifier.Presigned presigned = SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", query),
			agora());

		assertThat(presigned).isNotNull();
		assertThat(SigV4Verifier.verifyPresigned(presigned, SECRET, agora())).isTrue();
		assertThat(SigV4Verifier.verifyPresigned(presigned, SECRET, agora().plusSeconds(899))).isTrue();
	}

	/** Passado o prazo a URL morre — sem tolerância para a frente. */
	@Test
	void url_vencida_nao_verifica() {
		var presigned = SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", queryCompleta("60")),
			agora());

		// 60 s de validade, e o teste 61 s depois: uma segunda de folga já é vencida.
		assertThat(SigV4Verifier.verifyPresigned(presigned, SECRET, agora().plusSeconds(61))).isFalse();
	}

	/**
	 * Acrescentar um parâmetro à URL quebra a assinatura.
	 *
	 * <p>É o ataque mais óbvio contra URL assinada: quem recebeu o link troca
	 * {@code response-content-disposition} ou {@code versionId} e o servidor obedece — desde que a
	 * consulta canônica não inclua o que não foi assinado.</p>
	 */
	@Test
	void parametro_a_mais_na_url_quebra_a_assinatura() {
		String adulterada = queryCompleta("900") + "&response-content-disposition=attachment";
		var presigned = SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", adulterada), agora());

		assertThat(SigV4Verifier.verifyPresigned(presigned, SECRET, agora())).isFalse();
	}

	/** O verbo faz parte da assinatura: uma URL de GET não serve para PUT. */
	@Test
	void url_de_get_nao_verifica_reconstruida_como_put() {
		var presigned = SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", queryCompleta("900")),
			agora());

		var comoPut = new SigV4Verifier.Request("PUT", presigned.request().path(), presigned.request().query(),
			presigned.request().headers(), presigned.request().payloadHash());
		var trocado = new SigV4Verifier.Presigned(comoPut, presigned.parsed(), presigned.expiraEm(),
			presigned.duracaoSegundos());

		assertThat(SigV4Verifier.verifyPresigned(trocado, SECRET, agora())).isFalse();
	}

	/** O caminho também: a URL de um objeto não abre outro. */
	@Test
	void url_de_um_objeto_nao_abre_outro() {
		var presigned = SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", queryCompleta("900")),
			agora());

		var outroCaminho = new SigV4Verifier.Request("GET", "/bkt/fotos/cao.jpg", presigned.request().query(),
			presigned.request().headers(), presigned.request().payloadHash());
		var trocado = new SigV4Verifier.Presigned(outroCaminho, presigned.parsed(), presigned.expiraEm(),
			presigned.duracaoSegundos());

		assertThat(SigV4Verifier.verifyPresigned(trocado, SECRET, agora())).isFalse();
	}

	@Test
	void segredo_errado_nao_verifica() {
		var presigned = SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", queryCompleta("900")),
			agora());

		assertThat(SigV4Verifier.verifyPresigned(presigned, "outro-segredo", agora())).isFalse();
	}

	/** {@code X-Amz-Expires} acima de 7 dias é "URL eterna com outro nome": recusada na leitura. */
	@Test
	void validade_acima_do_teto_e_recusada() {
		String eterna = queryCompleta(String.valueOf(SigV4Verifier.MAX_PRESIGNED_SECONDS + 1));

		assertThat(SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", eterna), agora())).isNull();
	}

	@Test
	void validade_zero_ou_negativa_e_recusada() {
		assertThat(SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", queryCompleta("0")), agora()))
			.isNull();
		assertThat(SigV4Verifier.parsePresigned(requisicao("GET", "/bkt/fotos/gato.jpg", queryCompleta("-1")), agora()))
			.isNull();
	}

	/** Sem {@code X-Amz-Signature} não é presigned — é a requisição de cabeçalho, e quem chama decide. */
	@Test
	void sem_assinatura_na_query_devolve_nulo() {
		assertThat(SigV4Verifier.parsePresigned(
			requisicao("GET", "/bkt/fotos/gato.jpg", "X-Amz-Date=" + AMZ_DATE), agora())).isNull();
	}

	// ---------------------------------------------------------------- ajuda

	/** A query COMPLETA, como o gateway a monta ao emitir — inclusive a assinatura. */
	private static String queryCompleta(String expires) {
		Map<String, String> base = queryBase(expires);
		Map<String, String> headers = Map.of("host", "s3.enois.ia.br", "x-amz-date", AMZ_DATE,
			"x-amz-content-sha256", "UNSIGNED-PAYLOAD");
		var assinado = new SigV4Verifier.Request("GET", "/bkt/fotos/gato.jpg", base, headers, "UNSIGNED-PAYLOAD");
		var parsed = new SigV4Verifier.Parsed(ACCESS_KEY, "enois-1", "s3", "host", "");
		String signature = SigV4Verifier.signature(assinado, parsed, SECRET, DATE_STAMP);
		return bruto(base) + "&X-Amz-Signature=" + signature;
	}

	private static Map<String, String> queryBase(String expires) {
		Map<String, String> query = new LinkedHashMap<>();
		query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
		query.put("X-Amz-Credential", ACCESS_KEY + "/" + DATE_STAMP + "/enois-1/s3/aws4_request");
		query.put("X-Amz-Date", AMZ_DATE);
		query.put("X-Amz-Expires", expires);
		query.put("X-Amz-SignedHeaders", "host");
		return query;
	}

	/**
	 * A query como texto de URL: como os SDKs escrevem, com o {@code /} do escopo <b>codificado</b>.
	 * Testar com o valor em claro provaria o parser, não o cliente.
	 */
	private static String bruto(Map<String, String> query) {
		StringBuilder out = new StringBuilder();
		query.forEach((name, value) -> {
			if (!out.isEmpty()) {
				out.append('&');
			}
			out.append(name).append('=').append(SigV4Verifier.uriEncode(value));
		});
		return out.toString();
	}

	/** A requisição como o servidor a recebe: query decodificada, host e nada de Authorization. */
	private static SigV4Verifier.Request requisicao(String metodo, String caminho, String query) {
		Map<String, String> decodificada = new LinkedHashMap<>();
		for (String par : query.split("&")) {
			String[] parte = par.split("=", 2);
			decodificada.put(decode(parte[0]), parte.length > 1 ? decode(parte[1]) : "");
		}
		return new SigV4Verifier.Request(metodo, caminho, decodificada,
			Map.of("host", "s3.enois.ia.br"), null);
	}

	private static String decode(String value) {
		return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static Instant agora() {
		return Instant.from(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(java.time.ZoneOffset.UTC).parse(AMZ_DATE));
	}
}