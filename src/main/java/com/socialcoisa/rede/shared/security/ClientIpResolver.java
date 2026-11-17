package com.socialcoisa.rede.shared.security;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Descobre de qual IP veio o pedido — o dado em que os limites por origem se apoiam.
 *
 * O tráfego entra por um proxy reverso (ALB na AWS antiga, Traefik em `docker-prod3` hoje), então
 * `getRemoteAddr()` é o IP do nó do proxy, não o de quem pediu: qualquer controle "por IP" que
 * lesse dali estaria limitando o mundo inteiro como se fosse um só. Foi exatamente isso que
 * aconteceu na migração pro Traefik — o IP do proxy (`31.220.102.169`, `docker-prod3`) não é mais
 * loopback/RFC1918 como o ALB antigo, então {@link #isTrustedProxy} rejeitava a conexão e todo
 * request caía no fallback de `getRemoteAddr()`, voltando sempre o IP do próprio proxy em vez do
 * cliente real — corrigido tornando a lista de proxies confiáveis configurável
 * (`security.trusted-proxy-ips`), em vez de só as faixas privadas fixas.
 *
 * A ordem das fontes: (1) `CF-Connecting-IP`, only when the hop our proxy saw (right-most
 * untrusted `X-Forwarded-For` hop) is a Cloudflare edge address ({@code security.cloudflare-ips}):
 * the origin is reachable without Cloudflare, and a client hitting it directly can send any
 * `CF-Connecting-IP`; (2) a cadeia do `X-Forwarded-For`, lida da direita para a esquerda pulando os
 * proxies confiáveis; (3) o `getRemoteAddr()`. No other header (`X-Real-IP`, ...) is ever read, and
 * headers count only when the immediate peer is a trusted proxy.
 *
 * <p>{@code security.trusted-proxy-ips} takes IPs or CIDRs. Loopback and private ranges stay
 * trusted by default (in-cluster ingress).</p>
 *
 * <p><b>Servidor do front.</b> As telas do app chamam a API pelo servidor do front (SSR), e nesse
 * caminho o salto que chega até nós é o do servidor dele: todas as contas que usam o mesmo front
 * caíam num balde de IP só — o teto de cadastro, de recuperação de senha e de reenvio de e-mail era
 * compartilhado por quem nem se conhece. O front repassa o IP do navegador <i>acrescentando-o ao fim
 * da própria cadeia</i>, e é exatamente o primeiro item a partir da direita que ainda não é proxy
 * nosso. Esse item, porém, só vale quando o último salto da conexão é um proxy nosso E a origem
 * declarada é a de um front do próprio app: um cliente que fala direto com a origem pode mandar a
 * cadeia que quiser, e sem essa dupla verificação bastaria reescrever o cabeçalho para escolher o
 * próprio IP. O {@code X-Client-Origin} <b>não</b> entra nesta decisão — ele é do navegador e serve
 * ao portão de origem, não à identidade de rede. O {@link #resolveNetwork} continua devolvendo a
 * rede, para o que precisa de bloqueio por origem física.</p>
 *
 * Sobre confiar no `X-Forwarded-For`: o cabeçalho pode vir forjado pelo cliente, e o item forjado
 * fica no MEIO da lista (o proxy acrescenta o IP real no fim) ou no COMEÇO (quando o proxy
 * substitui o cabeçalho e o cliente mandou um). Por isso a cadeia é lida da direita para a
 * esquerda, pulando os proxies confiáveis: o primeiro salto que não é proxy é o IP da pessoa —
 * com qualquer convenção do proxy, e sem o cliente poder escolher o próprio.
 */
@Component
public class ClientIpResolver {

	private static final int MAX_IP = 45;

	/** Cloudflare's published edge ranges (https://www.cloudflare.com/ips/). */
	public static final String CLOUDFLARE_RANGES = "173.245.48.0/20,103.21.244.0/22,103.22.200.0/22,"
		+ "103.31.4.0/22,141.101.64.0/18,108.162.192.0/18,190.93.240.0/20,188.114.96.0/20,197.234.240.0/22,"
		+ "198.41.128.0/17,162.158.0.0/15,104.16.0.0/13,104.24.0.0/14,172.64.0.0/13,131.0.72.0/22,"
		+ "2400:cb00::/32,2606:4700::/32,2803:f800::/32,2405:b500::/32,2405:8100::/32,2a06:98c0::/29,"
		+ "2c0f:f248::/32";

	private final List<Cidr> trustedProxies;
	private final List<Cidr> cloudflareRanges;
	/** Origens HTTPS dos fronts do próprio app que repassam o IP do navegador. */
	private final Set<String> repassingFronts;

	/** Default sem proxy extra confiável — usado pelos testes, mantém o comportamento anterior. */
	public ClientIpResolver() {
		this("");
	}

	public ClientIpResolver(String trustedProxyIps) {
		this(trustedProxyIps, CLOUDFLARE_RANGES, "");
	}

	/** Sem proxies extras, com fronts do app que repassam o IP do navegador. */
	public static ClientIpResolver withRepassingFronts(String repassingFrontOrigins) {
		return new ClientIpResolver(List.of(), Cidr.parseList(CLOUDFLARE_RANGES), repassingFrontOrigins);
	}

	/** Caminho único de construção: cada construtor público só escolhe os valores default. */
	private ClientIpResolver(List<Cidr> trustedProxies, List<Cidr> cloudflareRanges, String repassingFrontOrigins) {
		this.trustedProxies = trustedProxies;
		this.cloudflareRanges = cloudflareRanges;
		this.repassingFronts = parseFronts(repassingFrontOrigins);
	}

	@Autowired
	public ClientIpResolver(@Value("${security.trusted-proxy-ips:}") String trustedProxyIps,
			@Value("${security.cloudflare-ips:" + CLOUDFLARE_RANGES + "}") String cloudflareIps,
			@Value("${security.repassing-front-origins:}") String repassingFrontOrigins) {
		this(Cidr.parseList(trustedProxyIps), Cidr.parseList(cloudflareIps), repassingFrontOrigins);
	}

	/**
	 * Só HTTPS conta: o front serve as páginas do app por TLS, e uma origem em HTTP claro não
	 * identifica servidor nenhum (qualquer um a escreve).
	 */
	private static Set<String> parseFronts(String value) {
		if (!StringUtils.hasText(value)) {
			return Set.of();
		}
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.map(origin -> origin.toLowerCase(Locale.ROOT))
			.filter(origin -> origin.startsWith("https://"))
			.map(origin -> origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	/**
	 * Identidade de rede usada pelos limites de identidade (cadastro, senha, reenvio de e-mail):
	 * o navegador repassado por um front nosso quando dá para provar; senão, o IP da conexão.
	 */
	public String resolve(HttpServletRequest request) {
		String repassed = repassedByOurFront(request);
		if (repassed != null) {
			return repassed;
		}
		return resolveNetwork(request);
	}

	/**
	 * A rede de onde o pedido saiu, sem o repasse do front: é a base do bloqueio de rede da
	 * moderação e do limite global — um bloqueio de rede não pode ser trocado por uma conta nova
	 * que abre outra sessão no mesmo front.
	 */
	public String resolveNetwork(HttpServletRequest request) {
		String remote = request.getRemoteAddr();
		if (!isTrustedProxy(remote)) {
			return isPlausible(remote) ? remote.trim() : "desconhecido";
		}
		// O Cloudflare (que fica na frente do Traefik em produção) escreve o IP de quem pediu
		// neste cabeçalho e o SOBRESCREVE em toda requisição que passa por ele — o cliente não
		// consegue forjá-lo. É a única fonte do IP real quando o proxy da frente reescreve o
		// X-Forwarded-For com o endereço da borda dele (os logs mostraram faixas 172.6x, do
		// próprio Cloudflare, para todo mundo). Só vale vindo de um proxy confiável.
		String forwarded = request.getHeader("X-Forwarded-For");
		if (StringUtils.hasText(forwarded)) {
			String candidate = clientHop(forwarded);
			if (isPlausible(candidate)) {
				String borda = request.getHeader("CF-Connecting-IP");
				if (isPlausible(borda) && Cidr.anyContains(cloudflareRanges, candidate)) {
					return borda.trim();
				}
				return candidate;
			}
			return isPlausible(remote) ? remote.trim() : "desconhecido";
		}
		return isPlausible(remote) ? remote.trim() : "desconhecido";
	}

	/**
	 * O IP do navegador, quando o pedido veio de um front do próprio app por um proxy nosso.
	 *
	 * <p>O front acrescenta o IP do navegador ao fim da cadeia que recebeu. Com a conexão chegando
	 * por um proxy nosso, esse item é o primeiro a partir da direita que não é proxy — exatamente o
	 * que {@code clientHop} devolve. O restante da cadeia vem do navegador e nunca é lido.</p>
	 */
	private String repassedByOurFront(HttpServletRequest request) {
		if (repassingFronts.isEmpty() || !isTrustedProxy(request.getRemoteAddr())) {
			return null;
		}
		if (!repassingFronts.contains(normalizeOrigin(request.getHeader("Origin")))) {
			return null;
		}
		String forwarded = request.getHeader("X-Forwarded-For");
		if (!StringUtils.hasText(forwarded)) {
			return null;
		}
		// O front não acrescentou nada (página sem navegador, chamada interna): a cadeia é a que o
		// proxy da frente gravou, e o item do fim é o IP do próprio front — não um cliente.
		int lastComma = forwarded.lastIndexOf(',');
		if (lastComma < 0 || !isTrustedProxy(forwarded.substring(lastComma + 1).trim())) {
			return null;
		}
		String candidate = clientHop(forwarded);
		return isPlausible(candidate) && !isTrustedProxy(candidate) ? candidate : null;
	}

	private static String normalizeOrigin(String origin) {
		String clean = origin == null ? "" : origin.trim().toLowerCase(Locale.ROOT);
		while (clean.endsWith("/")) {
			clean = clean.substring(0, clean.length() - 1);
		}
		return clean;
	}

	/** Is this address one of our own proxies (loopback, private, or configured)? */
	public boolean isTrustedProxy(String ip) {
		if (!StringUtils.hasText(ip)) {
			return false;
		}
		String value = ip.trim().toLowerCase(Locale.ROOT);
		if (value.startsWith("::ffff:")) {
			value = value.substring(7);
		}
		return isBuiltInTrusted(value) || Cidr.anyContains(trustedProxies, value);
	}

	/** Loopback e faixas privadas: onde vivem nginx/ALB/ingress dentro da rede. */
	private static boolean isBuiltInTrusted(String value) {
		if (value.equals("127.0.0.1") || value.equals("::1") || value.equals("0:0:0:0:0:0:0:1")) {
			return true;
		}
		if (value.startsWith("10.") || value.startsWith("192.168.") || value.startsWith("169.254.")) {
			return true;
		}
		if (value.startsWith("172.")) {
			String[] parts = value.split("\\.");
			if (parts.length > 1) {
				try {
					int second = Integer.parseInt(parts[1]);
					return second >= 16 && second <= 31;
				}
				catch (NumberFormatException ignored) {
					return false;
				}
			}
		}
		return value.startsWith("fc") || value.startsWith("fd") || value.startsWith("fe80:");
	}

	/**
	 * O IP do cliente dentro da cadeia do `X-Forwarded-For`.
	 *
	 * <p>A cadeia é lida <strong>da direita para a esquerda</strong>, pulando os saltos que são
	 * proxies confiáveis. O único item da cadeia que o <strong>cliente</strong> controla é o mais
	 * à esquerda — e é exatamente esse que não pode valer: se ele valer, cada pessoa escolhe o
	 * próprio IP e escapa de todo limite por IP (e o bloqueio da moderação vira decorativo).
	 *
	 * <p>Assim a leitura serve a qualquer convenção do proxy:
	 * <ul>
	 * <li>proxy que <strong>substitui</strong> o cabeçalho (Traefik sem confiar em XFF de
	 * qualquer origem): a cadeia tem um item só, o IP real → é o primeiro não-confiável a partir
	 * da direita;</li>
	 * <li>proxy que <strong>acrescenta</strong> o IP de quem se conectou (ALB): a cadeia é
	 * "forjado, real" → da direita, "real" não é proxy confiável, é ele; o "forjado" (do cliente)
	 * nunca é alcançado;</li>
	 * <li>cadeia com proxies no meio: os confiáveis são pulados e para no primeiro que não é.</li>
	 * </ul>
	 */
	private String clientHop(String header) {
		String[] saltos = header.split(",");
		for (int i = saltos.length - 1; i >= 0; i--) {
			String salto = saltos[i].trim();
			if (!isPlausible(salto)) {
				continue;
			}
			if (isTrustedProxy(salto)) {
				continue;
			}
			return salto;
		}
		return "";
	}

	private boolean isPlausible(String value) {
		if (!StringUtils.hasText(value) || value.length() > MAX_IP || StringUtils.containsWhitespace(value)) {
			return false;
		}
		return value.matches("[0-9a-fA-F:.]{3,}");
	}

	/** IPv4/IPv6 network; a bare IP is a /32 or /128. Only literal addresses are parsed (no DNS). */
	record Cidr(byte[] network, int prefix) {

		static List<Cidr> parseList(String value) {
			if (!StringUtils.hasText(value)) {
				return List.of();
			}
			return Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(StringUtils::hasText)
				.map(Cidr::parse)
				.toList();
		}

		static Cidr parse(String value) {
			int slash = value.indexOf('/');
			byte[] address = literal(slash < 0 ? value : value.substring(0, slash));
			if (address == null) {
				throw new IllegalStateException("Invalid IP/CIDR in proxy configuration: " + value);
			}
			int prefix = slash < 0 ? address.length * 8 : Integer.parseInt(value.substring(slash + 1));
			return new Cidr(address, prefix);
		}

		static boolean anyContains(List<Cidr> ranges, String ip) {
			byte[] address = literal(ip);
			return address != null && ranges.stream().anyMatch(range -> range.contains(address));
		}

		boolean contains(byte[] address) {
			if (address.length != network.length) {
				return false;
			}
			int full = prefix / 8;
			for (int i = 0; i < full; i++) {
				if (address[i] != network[i]) {
					return false;
				}
			}
			int rest = prefix % 8;
			if (rest == 0) {
				return true;
			}
			int mask = (0xFF << (8 - rest)) & 0xFF;
			return (address[full] & mask) == (network[full] & mask);
		}

		private static byte[] literal(String ip) {
			if (ip == null) {
				return null;
			}
			String value = ip.trim();
			boolean ipv4 = value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
			if (!ipv4 && !(value.contains(":") && value.matches("[0-9a-fA-F:.]+"))) {
				return null;
			}
			try {
				return java.net.InetAddress.getByName(value).getAddress();
			}
			catch (java.net.UnknownHostException invalid) {
				return null;
			}
		}
	}
}
