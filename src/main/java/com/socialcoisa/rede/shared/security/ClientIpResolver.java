package com.socialcoisa.rede.shared.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

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
 * Sobre confiar no `X-Forwarded-For`: o cabeçalho pode vir forjado pelo cliente, mas o proxy da
 * nossa rede **acrescenta** o IP de quem se conectou ao FIM da lista. Logo o ÚLTIMO item é o único
 * que nós (e não o cliente) escrevemos — é ele que vale. Os itens anteriores são histórico que o
 * cliente pode ter inventado, e por isso não entram na decisão.
 *
 * O Traefik coloca o IP real como primeiro (ou único) item do XFF. O ALB da AWS colocava
 * no fim. O resolver lê o PRIMEIRO hop do XFF (o mais à esquerda), que é o IP do cliente
 * original. Itens subsequentes são proxies intermediários.
 */
@Component
public class ClientIpResolver {

	private static final int MAX_IP = 45;

	private final Set<String> trustedProxyIps;

	/** Default sem proxy extra confiável — usado pelos testes, mantém o comportamento anterior. */
	public ClientIpResolver() {
		this("");
	}

	@Autowired
	public ClientIpResolver(@Value("${security.trusted-proxy-ips:}") String trustedProxyIps) {
		this.trustedProxyIps = Arrays.stream(trustedProxyIps.split(","))
			.map(String::trim)
			.map(ip -> ip.toLowerCase(java.util.Locale.ROOT))
			.filter(StringUtils::hasText)
			.collect(Collectors.toUnmodifiableSet());
	}

	public String resolve(HttpServletRequest request) {
		String remote = request.getRemoteAddr();
		if (!isTrustedProxy(remote)) {
			return isPlausible(remote) ? remote.trim() : "desconhecido";
		}
		String forwarded = request.getHeader("X-Forwarded-For");
		if (StringUtils.hasText(forwarded)) {
			String candidate = firstHop(forwarded);
			if (isPlausible(candidate)) {
				return candidate;
			}
			return isPlausible(remote) ? remote.trim() : "desconhecido";
		}
		return isPlausible(remote) ? remote.trim() : "desconhecido";
	}

	/** Loopback, faixas privadas e o(s) IP(s) do proxy reverso atual: onde vivem nginx/ALB/Traefik. */
	private boolean isTrustedProxy(String ip) {
		if (!StringUtils.hasText(ip)) {
			return false;
		}
		String value = ip.trim().toLowerCase(Locale.ROOT);
		if (value.startsWith("::ffff:")) {
			value = value.substring(7);
		}
		if (trustedProxyIps.contains(value)) {
			return true;
		}
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
	 * Primeiro item do XFF — o IP do cliente original. O Traefik coloca o IP real do
	 * cliente como primeiro item; proxies intermediários são adicionados depois.
	 */
	private String firstHop(String header) {
		int firstComma = header.indexOf(',');
		return firstComma < 0 ? header.trim() : header.substring(0, firstComma).trim();
	}

	private boolean isPlausible(String value) {
		if (!StringUtils.hasText(value) || value.length() > MAX_IP || StringUtils.containsWhitespace(value)) {
			return false;
		}
		return value.matches("[0-9a-fA-F:.]{3,}");
	}
}
