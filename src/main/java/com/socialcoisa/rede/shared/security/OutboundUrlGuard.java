package com.socialcoisa.rede.shared.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Guarda contra SSRF: valida uma URL **fornecida pelo usuário** antes de o backend buscar o
 * conteúdo dela. Um anexo de suporte apontando para `http://169.254.169.254/...` (metadados da
 * nuvem), para um serviço interno do cluster ou para o banco não pode virar requisição nossa.
 *
 * Regras: só http/https, só portas padrão (80/443), host que não seja literal de loopback/
 * faixa privada/link-local e cujo DNS resolva apenas para endereços públicos. Falha é negativa:
 * quem chama trata como "não busca".
 *
 * <p>{@link #safe(String)} sozinho não basta quando o cliente faz o DNS de novo na hora de
 * conectar (um registro que muda de IPv4 depois da checagem conecta onde a guarda nunca viu).
 * Quem busca conteúdo deve usar {@link #checked(String)}, que devolve o endereço já resolvido e
 * exige que a conexão vá para ELE — sempre pelo último IP validado, de qualquer família.</p>
 */
@Component
public class OutboundUrlGuard {

	private static final Logger LOG = LoggerFactory.getLogger(OutboundUrlGuard.class);

	private static final Set<String> ALLOWED_PORTS = Set.of("80", "443", "-1", "");

	public boolean safe(String url) {
		return checked(url).isPresent();
	}

	/**
	 * O destino validado, com os IPs públicos para os quais o nome resolveu nesta chamada.
	 *
	 * <p>Vazio quando a URL não passa em nenhuma regra ou quando QUALQUER IP resolvido é interno:
	 * basta um registro privado para o nome inteiro ser recusado.</p>
	 */
	public Optional<CheckedUrl> checked(String url) {
		if (url == null || url.isBlank() || url.length() > 2000) {
			return Optional.empty();
		}
		URI uri;
		try {
			uri = URI.create(url.trim());
		}
		catch (RuntimeException e) {
			return Optional.empty();
		}
		String scheme = uri.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			return Optional.empty();
		}
		if (!ALLOWED_PORTS.contains(String.valueOf(uri.getPort()))) {
			return Optional.empty();
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			return Optional.empty();
		}
		String lower = host.toLowerCase();
		if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".internal")
			|| lower.endsWith(".local") || lower.equals("metadata.google.internal")) {
			return Optional.empty();
		}
		try {
			// Resolução cobre nome e literal: um IP público escrito direto também passa por aqui.
			InetAddress[] addresses = InetAddress.getAllByName(host);
			if (addresses.length == 0 || !Arrays.stream(addresses).allMatch(OutboundUrlGuard::isPublic)) {
				return Optional.empty();
			}
			return Optional.of(new CheckedUrl(uri, host.toLowerCase(), addresses));
		}
		catch (Exception e) {
			LOG.debug("OutboundUrlGuard: host {} nao resolveu: {}", host, e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Destino aprovado e o resultado do DNS que o aprovou. Os IPs estão na ordem em que o DNS
	 * respondeu; o cliente tenta um por um (IPv4 e IPv6) e nunca resolve o nome de novo.
	 */
	public record CheckedUrl(URI uri, String host, InetAddress[] addresses) {

		public boolean secure() {
			return "https".equalsIgnoreCase(uri.getScheme());
		}

		/** Porta efetiva do destino (80/443 quando a URL não declara). */
		public int port() {
			if (uri.getPort() > 0) {
				return uri.getPort();
			}
			return secure() ? 443 : 80;
		}

		/** Caminho com a query, como o servidor de destino espera receber. */
		public String requestTarget() {
			String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			String query = uri.getRawQuery();
			return query == null ? path : path + "?" + query;
		}

		/** O cabeçalho Host leva a porta só quando ela não é a padrão. */
		public String hostHeader() {
			int port = uri.getPort();
			boolean defaultPort = port < 0 || (secure() && port == 443) || (!secure() && port == 80);
			return defaultPort ? host : host + ":" + port;
		}
	}

	private static boolean isPublic(InetAddress address) {
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
			|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return false;
		}
		byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			return !isPrivateV4(bytes);
		}
		if (bytes.length == 16) {
			return !isPrivateV6(bytes);
		}
		return false;
	}

	private static boolean isPrivateV4(byte[] b) {
		int first = b[0] & 0xFF;
		int second = b[1] & 0xFF;
		// 10/8, 172.16/12, 192.168/16, 100.64/10 (CGNAT), 0/8 e 198.18/15 (bench)
		return first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168)
			|| (first == 100 && second >= 64 && second <= 127) || first == 0 || first == 198 || first == 127;
	}

	private static boolean isPrivateV6(byte[] b) {
		// fc00::/7 (unique local), fe80::/10 (link local), ::1 e mapeamento IPv4 (::ffff:x.x.x.x)
		if ((b[0] & 0xFE) == 0xFC || (b[0] == 0xFE && (b[1] & 0xC0) == 0x80)) {
			return true;
		}
		boolean allZero = true;
		for (int i = 0; i < 15; i++) {
			if (b[i] != 0) {
				allZero = false;
				break;
			}
		}
		if (allZero && b[15] == 1) {
			return true; // ::1
		}
		boolean v4Mapped = true;
		for (int i = 0; i < 10; i++) {
			if (b[i] != 0) {
				v4Mapped = false;
				break;
			}
		}
		if (v4Mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
			return isPrivateV4(new byte[] { b[12], b[13], b[14], b[15] });
		}
		return false;
	}
}
