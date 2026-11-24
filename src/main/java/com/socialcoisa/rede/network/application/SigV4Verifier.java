package com.socialcoisa.rede.network.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifica a assinatura <b>AWS Signature Version 4</b> de uma requisição S3 (brief §11).
 *
 * <p><b>Por que ter isso:</b> sem SigV4 nenhum SDK S3 funciona — o SDK assina cada requisição e não
 * abre exceção. É a condição para "permitir utilização de SDKs S3 convencionais".</p>
 *
 * <p><b>O que este verificador NÃO faz</b>, e por que não é descuido:</p>
 * <ul>
 *   <li><b>não busca o segredo em claro</b> — recebe-o de quem chamou, porque o SigV4 exige
 *   recalcular o HMAC. Se o segredo só está em hash, a requisição é recusada; essa é a lacuna
 *   declarada na doc do gateway, não um "talvez";</li>
 *   <li><b>não valida a região</b> no sentido da AWS: a região entra no cálculo como qualquer outra
 *   string. Uma região errada simplesmente não confere a assinatura. Não há tabela de regiões S3
 *   porque a rede não é particionada por região S3 — é a mesma lógica de região lógica da §2;</li>
 *   <li><b>não aceita payload não assinado</b> (sem {@code x-amz-content-sha256}) quando o cabeçalho
 *   de payload está ausente: sem ele não há como conferir que o corpo é o assinado. Com
 *   {@code UNSIGNED-PAYLOAD} o corpo não é conferido, e isso é aceito só em</li>
 *   <li>requisições que declaram {@code UNSIGNED-PAYLOAD} explicitamente.</li>
 * </ul>
 *
 * <p>Implementação segue a especificação: canonical request → string to sign → chave de assinatura
 * derivada (HMAC em cadeia: data, região, serviço, {@code aws4_request}) → HMAC-SHA256 comparado em
 * tempo constante.</p>
 */
public final class SigV4Verifier {

	/** Janela de tolerância do relógio do cliente, em segundos (a AWS usa 15 min). */
	public static final long CLOCK_SKEW_SECONDS = 900;

	/** Teto de validade de uma URL pré-assinada: o mesmo da AWS (7 dias). */
	public static final long MAX_PRESIGNED_SECONDS = 7L * 24 * 60 * 60;

	private static final Pattern AUTH = Pattern.compile(
		"AWS4-HMAC-SHA256\\s+Credential=([^,\\s]+)\\s*,\\s*SignedHeaders=([^,\\s]+)\\s*,\\s*Signature=([0-9a-f]{64})",
		Pattern.CASE_INSENSITIVE);

	private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private SigV4Verifier() {
	}

	/** O que a verificação devolveu. */
	public record Parsed(String accessKey, String region, String service, String signedHeaders,
			String signature) {
	}

	/**
	 * Uma requisição a verificar, já reduzida ao que o cálculo precisa.
	 *
	 * @param method   verbo HTTP
	 * @param path     caminho canônico (o {@code /bucket/key} como o cliente montou, sem query)
	 * @param query    parâmetros de consulta com valores **já decodificados**; a canonicalização
	 *                 recodifica (um cliente que manda {@code list-type=2} e outro que manda
	 *                 {@code list-type=%32} têm de conferir igual)
	 * @param headers  cabeçalhos que o cliente declarou em {@code SignedHeaders}, com valores já
	 *                 normalizados por {@link #normalizeValue(String)}; nomes em minúsculas
	 * @param payloadHash o que o cliente declarou em {@code x-amz-content-sha256} (ou o hash do corpo)
	 */
	public record Request(String method, String path, Map<String, String> query, Map<String, String> headers,
			String payloadHash) {
	}

	/**
	 * Extrai o que dá para extrair do header {@code Authorization}, ou {@code null} se ele não é SigV4.
	 *
	 * <p>{@code null} não é erro de assinatura: pode ser requisição anônima, {@code presigned} ou o
	 * esquema antigo. Quem chama decide.</p>
	 */
	/**
	 * Região do S3 global: o cliente que não configura região assina {@code us-east-1}.
	 *
	 * <p>A AWS aceita {@code us-east-1} em qualquer bucket — o SigV4 do S3 é global —, e é o padrão
	 * do {@code AWS CLI}. Recusar só porque a instalação se declara {@code enois-1} quebraria todo
	 * cliente que nunca escolheu região.</p>
	 */
	public static final String REGION_GLOBAL = "us-east-1";

	/**
	 * O serviço e a região do escopo são os desta instalação (§4.56).
	 *
	 * <p><b>Antes:</b> o escopo era lido e usado na chave de assinatura, mas nunca conferido — uma
	 * requisição assinada para {@code ec2} ou para qualquer região era aceita. O segredo é o mesmo,
	 * então a assinatura confere; o escopo é a única coisa que diz para o quê o cliente assinou.</p>
	 *
	 * @param region a região que a instalação declara; {@link #REGION_GLOBAL} é sempre aceita
	 * @return {@code null} se o escopo é válido, ou o motivo da recusa
	 */
	public static String motivoDeEscopoInvalido(Parsed parsed, String region) {
		if (parsed == null) {
			return null;
		}
		if (!"s3".equals(parsed.service())) {
			return "A assinatura e para o servico '" + parsed.service() + "', e este gateway atende 's3'.";
		}
		if (!region.equals(parsed.region()) && !REGION_GLOBAL.equals(parsed.region())) {
			return "A assinatura e para a regiao '" + parsed.region() + "', e este gateway atende '"
				+ region + "' ou '" + REGION_GLOBAL + "'.";
		}
		return null;
	}

	public static Parsed parseAuthorization(String authorization) {
		if (authorization == null) {
			return null;
		}
		Matcher m = AUTH.matcher(authorization.trim());
		if (!m.matches()) {
			return null;
		}
		String[] credential = m.group(1).split("/");
		if (credential.length != 5 || !"aws4_request".equals(credential[4])) {
			return null;
		}
		return new Parsed(credential[0], credential[2], credential[3], m.group(2).toLowerCase(Locale.ROOT),
			m.group(3));
	}

	/**
	 * Lê a credencial e a assinatura de uma <b>URL pré-assinada</b> (brief §11) — a assinatura vem na
	 * <i>query</i>, não no cabeçalho.
	 *
	 * <p>A forma é a mesma do SigV4 de cabeçalho, com uma diferença de origem: o cliente que vai usar a
	 * URL não manda {@code Authorization}. Por isso o {@code SignedHeaders} e o {@code X-Amz-Date}
	 * também podem vir na query, e é de lá que se lê. O {@link Request} devolvido já traz esses valores
	 * nos cabeçalhos, para que a assinatura seja calculada exatamente pelo mesmo caminho.</p>
	 *
	 * <p>Devolve {@code null} quando não há {@code X-Amz-Signature} — não é erro: pode ser uma
	 * requisição assinada por cabeçalho, e quem chama decide.</p>
	 */
	public static Presigned parsePresigned(Request request, Instant now) {
		Map<String, String> query = request.query();
		String signature = query.get("X-Amz-Signature");
		String credentialText = query.get("X-Amz-Credential");
		if (signature == null || credentialText == null) {
			return null;
		}
		String[] campos = credentialText.split("/");
		if (campos.length != 5 || !"aws4_request".equals(campos[4])) {
			return null;
		}
		String signedHeaders = query.getOrDefault("X-Amz-SignedHeaders", "host");
		String amzDate = query.get("X-Amz-Date");
		if (amzDate == null) {
			return null;
		}
		// O `X-Amz-Credential` normalizado: a consulta canônica o codifica de novo, então o valor
		// canônico é `campos` unidos por `/` — o mesmo texto que o emissor assinou, tenha o servidor
		// entregado o `/` cru ou o `%2F`.
		String credentialCanonica = String.join("/", campos);
		// O hash do corpo em URL pré-assinada MOR S3 é `UNSIGNED-PAYLOAD`: a URL é gerada sem o corpo e
		// vale para qualquer corpo até expirar. Aceitar outro valor aqui daria uma URL que só funciona
		// para um corpo — e o cliente não saberia disso pela URL.
		String payloadHash = query.getOrDefault("X-Amz-Content-Sha256", "UNSIGNED-PAYLOAD");

		// Os parâmetros de assinatura NÃO entram na consulta canônica — é a regra que separa a
		// assinatura do que ela assina. Manter `X-Amz-Signature` nela faria a verificação depender da
		// própria assinatura e nunca conferir.
		Map<String, String> assinada = new TreeMap<>(query);
		assinada.remove("X-Amz-Signature");
		assinada.put("X-Amz-Credential", credentialCanonica);

		Map<String, String> headers = new TreeMap<>(request.headers());
		headers.put("x-amz-date", amzDate);
		headers.put("x-amz-content-sha256", payloadHash);
		if (headers.get("host") == null) {
			// O `host` está em SignedHeaders por quase todo SDK; sem ele a assinatura canônica sai com
			// linha vazia e nada confere.
			return null;
		}

		var canonical = new Request(request.method(), request.path(), assinada, headers, payloadHash);
		var parsed = new Parsed(campos[0], campos[2], campos[3], signedHeaders.toLowerCase(Locale.ROOT), signature);
		Instant expira;
		long duracao;
		String expiresText = query.get("X-Amz-Expires");
		try {
			duracao = Long.parseLong(expiresText);
			if (duracao <= 0 || duracao > MAX_PRESIGNED_SECONDS) {
				// Negativo = já nasceu vencida; acima do teto = "eterna" com outro nome. O S3 recusa
				// acima de 7 dias, e uma URL que não expira é uma credencial de longa duração disfarçada.
				return null;
			}
			expira = amzDate(amzDate).plusSeconds(duracao);
		}
		catch (RuntimeException invalido) {
			// Sem `X-Amz-Expires` legível não há validade: uma URL eternamente válida é pior do que
			// uma recusada.
			return null;
		}
		return new Presigned(canonical, parsed, expira, duracao);
	}

	/**
	 * A URL pré-assinada confere e ainda vale?
	 *
	 * <p>Duas coisas que o SigV4 de cabeçalho não precisa e esta precisa: a <b>expiração</b> — uma URL
	 * assinada vive fora do relógio do servidor, então a janela é a dela, não {@link #CLOCK_SKEW_SECONDS}
	 * — e a recusa de {@code X-Amz-Expires} absurdo (negativo ou acima do teto do S3, 7 dias), que seria
	 * uma URL eternamente válida escrita com outro nome.</p>
	 */
	public static boolean verifyPresigned(Presigned presigned, String secret, Instant now) {
		if (presigned == null || secret == null || secret.isBlank()) {
			return false;
		}
		long expiracao = presigned.expiraEm().getEpochSecond();
		long agora = now.getEpochSecond();
		if (agora > expiracao) {
			return false;
		}
		// Tolerância só para trás: um relógio do cliente adiantado não pode esticar a URL; um atrasado,
		// sim, porque quem usa a URL logo depois de gerá-la pode estar abaixo do segundo.
		long nascimento = expiracao - presigned.duracaoSegundos();
		if (agora < nascimento - CLOCK_SKEW_SECONDS) {
			return false;
		}
		String amzDate = presigned.request().headers().get("x-amz-date");
		String calculada = signature(presigned.request(), presigned.parsed(), secret, amzDate.substring(0, 8));
		return constantTimeEquals(calculada, presigned.parsed().signature());
	}

	private static Instant amzDate(String value) {
		return Instant.from(AMZ_DATE.parse(value));
	}

	/** O que uma URL pré-assinada carrega. */
	public record Presigned(Request request, Parsed parsed, Instant expiraEm, long duracaoSegundos) {
	}

	/**
	 * A requisição confere? {@code false} em qualquer divergência — a mensagem fica com quem chama,
	 * porque dizer no corpo <i>qual</i> parte divergiu ajudaria a forjar.
	 */
	public static boolean verify(Request request, Parsed parsed, String secret, Instant now) {
		if (parsed == null || secret == null || secret.isBlank()) {
			return false;
		}
		String amzDate = request.headers().get("x-amz-date");
		if (amzDate == null) {
			return false;
		}
		Instant when;
		try {
			when = Instant.from(AMZ_DATE.parse(amzDate));
		}
		catch (RuntimeException invalido) {
			return false;
		}
		// O `x-amz-date` é truncado em segundos: um relógio de cliente que esteja 30 s à frente do
		// servidor assina com 30 s a menos e a diferença nunca passa da tolerância contada dali.
		// A comparação é por segundo inteiro, dos dois lados, senão a fração de segundo do `now`
		// faz a borda oscilar (mesma requisição aceita ou recusada conforme o nanossegundo).
		if (Math.abs(now.getEpochSecond() - when.getEpochSecond()) > CLOCK_SKEW_SECONDS) {
			return false;
		}
		String calculated = signature(request, parsed, secret, amzDate.substring(0, 8));
		return constantTimeEquals(calculated, parsed.signature());
	}

	/** A assinatura que o cliente deveria ter mandado. Público para o teste conferir contra vetor. */
	public static String signature(Request request, Parsed parsed, String secret, String dateStamp) {
		String canonicalRequest = canonicalRequest(request, parsed);
		String scope = dateStamp + "/" + parsed.region() + "/" + parsed.service() + "/aws4_request";
		String stringToSign = "AWS4-HMAC-SHA256\n" + request.headers().get("x-amz-date") + "\n" + scope + "\n"
			+ HexFormat.of().formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
		byte[] key = signingKey(secret, dateStamp, parsed.region(), parsed.service());
		return HexFormat.of().formatHex(hmac(key, stringToSign));
	}

	/**
	 * O canonical request. Os cabeçalhos vêm na ordem de {@code SignedHeaders}, que o cliente já
	 * declarou ordenada — não reordenamos: a ordem declarada faz parte da assinatura, e reordenar
	 * aceitaria um {@code SignedHeaders} fora de ordem que o cliente não assinou.
	 */
	public static String canonicalRequest(Request request, Parsed parsed) {
		StringBuilder canonical = new StringBuilder();
		canonical.append(request.method().toUpperCase(Locale.ROOT)).append('\n');
		canonical.append(canonicalUri(request.path())).append('\n');
		canonical.append(canonicalQuery(request.query())).append('\n');
		for (String name : parsed.signedHeaders().split(";")) {
			String value = request.headers().get(name);
			canonical.append(name).append(':').append(value == null ? "" : value).append('\n');
		}
		canonical.append('\n');
		canonical.append(parsed.signedHeaders()).append('\n');
		canonical.append(request.payloadHash() == null ? "" : request.payloadHash());
		return canonical.toString();
	}

	/**
	 * A URI canônica: cada segmento codificado uma vez, {@code /} preservado.
	 *
	 * <p>O S3 é especial na AWS (não codifica o caminho duas vezes), e por isso a comparação usa o
	 * {@code x-amz-content-sha256} para decidir o modo. Aqui só existe o modo "codifica o caminho",
	 * que é o que o SDK usa contra endpoint personalizado — e a doc diz isso explicitamente.</p>
	 */
	static String canonicalUri(String path) {
		if (path == null || path.isEmpty()) {
			return "/";
		}
		StringBuilder out = new StringBuilder();
		for (String segment : path.split("/", -1)) {
			if (!out.isEmpty() || path.startsWith("/")) {
				// o split com -1 devolve o primeiro vazio quando o caminho começa com /
			}
			out.append('/');
			out.append(uriEncode(segment));
		}
		// `split` de "/a/b" → ["", "a", "b"]: o laço acima gera "//a/b"; tira a duplicata da raiz.
		if (out.length() > 1 && out.charAt(1) == '/') {
			out.deleteCharAt(0);
		}
		return out.toString().isEmpty() ? "/" : out.toString();
	}

	/** Consulta canônica: nomes e valores codificados, ordenados por nome e depois por valor. */
	static String canonicalQuery(Map<String, String> query) {
		if (query == null || query.isEmpty()) {
			return "";
		}
		TreeMap<String, List<String>> porNome = new TreeMap<>();
		for (Map.Entry<String, String> e : query.entrySet()) {
			porNome.computeIfAbsent(uriEncode(e.getKey()), k -> new ArrayList<>()).add(uriEncode(e.getValue()));
		}
		List<String> pairs = new ArrayList<>();
		porNome.forEach((name, values) -> {
			values.sort(null);
			for (String value : values) {
				pairs.add(name + "=" + value);
			}
		});
		return String.join("&", pairs);
	}

	/**
	 * RFC 3986 com o conjunto que a AWS usa: letras, dígitos, {@code -_.~} passam; o resto vira
	 * {@code %XX} maiúsculo em UTF-8. Espaço vira {@code %20}, nunca {@code +}.
	 */
	public static String uriEncode(String value) {
		if (value == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xFF;
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
					|| c == '.' || c == '~') {
				out.append((char) c);
			}
			else {
				out.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
			}
		}
		return out.toString();
	}

	/**
	 * Valor de cabeçalho canônico: aparar, colapsar espaços internos e unir repetidos com vírgula.
	 * O cliente assina o valor já normalizado, então o gateway compara o mesmo texto.
	 */
	public static String normalizeValue(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().replaceAll("\\s+", " ");
	}

	private static byte[] signingKey(String secret, String dateStamp, String region, String service) {
		byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
		byte[] kRegion = hmac(kDate, region);
		byte[] kService = hmac(kRegion, service);
		return hmac(kService, "aws4_request");
	}

	private static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		}
		catch (java.security.GeneralSecurityException falha) {
			// HmacSHA256 é obrigatório pela JCA; não cair aqui é invariante da plataforma.
			throw new IllegalStateException("HmacSHA256 indisponivel", falha);
		}
	}

	private static byte[] sha256(byte[] data) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(data);
		}
		catch (java.security.NoSuchAlgorithmException falha) {
			throw new IllegalStateException("SHA-256 indisponivel", falha);
		}
	}

	/** Comparação em tempo constante: comparar com `equals` daria o prefixo correto por temporização. */
	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null || a.length() != b.length()) {
			return false;
		}
		int diff = 0;
		for (int i = 0; i < a.length(); i++) {
			diff |= a.charAt(i) ^ b.charAt(i);
		}
		return diff == 0;
	}
}