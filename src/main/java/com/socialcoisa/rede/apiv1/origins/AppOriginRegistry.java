package com.socialcoisa.rede.apiv1.origins;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * A lista de origens do próprio app — FONTE ÚNICA para o portão de origem, para o CORS e para o
 * guarda de abuso.
 *
 * Antes havia duas listas com regras diferentes: o portão aceitava curinga de subdomínio
 * ({@code https://*.b7cloud.tech}) e o CORS o removia, então a mesma origem passava num lugar e era
 * recusada no outro. A diferença entre as duas não é a lista, é o que cada uma pode conceder:
 *
 * <ul>
 * <li><b>Confiável</b> ({@link #trusted()}): origens explícitas de {@code rede.cors.allowed-origins}
 *   mais os padrões de {@code rede.origins.trusted}. Aceita curinga de subdomínio, e vale para
 *   todas as rotas.</li>
 * <li><b>Credenciável</b> ({@link #credentialed()}): só as explícitas. É o que pode receber o cookie
 *   de sessão (CORS com credenciais e rotas de admin): conceder credencial a um curinga entrega a
 *   sessão de qualquer pessoa a qualquer vizinho de subdomínio.</li>
 * </ul>
 *
 * <p>O curinga de subdomínio só vale nos perfis de QA e desenvolvimento
 * ({@code rede.origins.wildcards-allowed}). Em produção a lista precisa ser de origens exatas: um
 * subdomínio novo — inclusive de terceiro que controla uma entrada DNS na família — não passa a ser
 * confiável sozinho. O curinga descartado fica registrado no log de boot, para a configuração
 * ignorada não passar em silêncio.</p>
 */
@Component
public class AppOriginRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(AppOriginRegistry.class);

	/** Sem configuração explícita, a origem conhecida do app. */
	static final String DEFAULT_ORIGIN = "https://enois.ia.br";

	private final Set<String> allowed;
	private final Set<String> trustedPatterns;
	private final Set<String> trusted;
	private final Set<String> credentialed;
	private final List<String> corsPatterns;
	private final boolean wildcardsAllowed;

	public AppOriginRegistry(
			@Value("${rede.cors.allowed-origins:}") String allowedOrigins,
			@Value("${rede.origins.trusted:}") String trustedOrigins,
			@Value("${rede.origins.wildcards-allowed:false}") boolean wildcardsAllowed) {
		// A lista de CORS é derivada no boot, não a cada requisição: montar um object array novo por
		// preflight (com o registro recebendo quem chama a API) descartava o cache do Spring para
		// todo pedido.
		Set<String> explicit = OriginPatterns.parse(StringUtils.hasText(allowedOrigins) ? allowedOrigins
			: DEFAULT_ORIGIN);
		Set<String> patterns = OriginPatterns.parse(trustedOrigins);
		rejectOpenWildcards(explicit, patterns);
		if (!wildcardsAllowed) {
			patterns = dropWildcards(patterns, "rede.origins.trusted");
			explicit = dropWildcards(explicit, "rede.cors.allowed-origins");
			// A origem conhecida do app nunca sai da lista, mesmo que o operador configure só curinga.
			if (explicit.isEmpty()) {
				explicit = Set.of(DEFAULT_ORIGIN);
			}
		}
		Set<String> all = new LinkedHashSet<>(explicit);
		all.addAll(patterns);
		this.allowed = explicit;
		this.trustedPatterns = patterns;
		this.trusted = Set.copyOf(all);
		this.credentialed = all.stream().filter(pattern -> !pattern.contains("*"))
			.collect(Collectors.toUnmodifiableSet());
		// As explícitas do app e, quando o perfil permite curinga, os padrões de confiança: o portão
		// aceita o subdomínio e o navegador precisa receber a resposta (sem credencial — o Spring só
		// manda Access-Control-Allow-Credentials quando a origem pedida está entre as credenciáveis).
		this.corsPatterns = List.copyOf(all);
		this.wildcardsAllowed = wildcardsAllowed;
	}

	/**
	 * {@code *} e {@code https://*} deixam qualquer site chamar a API: valem como erro de
	 * configuração em qualquer perfil, e o boot falha em vez de servir aberto.
	 */
	private static void rejectOpenWildcards(Set<String> explicit, Set<String> patterns) {
		Set<String> together = new LinkedHashSet<>(explicit);
		together.addAll(patterns);
		for (String pattern : together) {
			if (pattern.equals("*") || pattern.endsWith("://*")) {
				throw new IllegalStateException(
					"Wildcard CORS origin '" + pattern + "' is not allowed; list the origins explicitly");
			}
		}
	}

	/**
	 * Produção: curinga não entra em nenhuma das duas listas. Remover em vez de falhar no boot deixa
	 * o operador ver no log que a configuração foi ignorada, sem derrubar o serviço.
	 */
	private static Set<String> dropWildcards(Set<String> from, String property) {
		Set<String> kept = new LinkedHashSet<>();
		for (String pattern : from) {
			if (pattern.contains("*")) {
				LOG.warn("curinga de origem ignorado neste perfil: {} nao aceita '{}'", property, pattern);
			}
			else {
				kept.add(pattern);
			}
		}
		return Set.copyOf(kept);
	}

	/** Origens explícitas configuradas (sem curinga). */
	public Set<String> allowed() {
		return allowed;
	}

	/** Padrões confiáveis configurados (podem conter curinga em QA/dev). */
	public Set<String> trustedPatterns() {
		return trustedPatterns;
	}

	/** Toda origem confiável: explícitas + padrões. */
	public Set<String> trusted() {
		return trusted;
	}

	/** As que podem receber CORS com credenciais e entrar em admin/moderação. */
	public Set<String> credentialed() {
		return credentialed;
	}

	public boolean wildcardsAllowed() {
		return wildcardsAllowed;
	}

	/** A origem (já na forma de comparação) é do próprio app? */
	public boolean isTrusted(String normalizedOrigin) {
		return OriginPatterns.matches(normalizedOrigin, trusted);
	}

	/** A origem é explícita do app — pode receber o cookie de sessão? */
	public boolean isCredentialed(String normalizedOrigin) {
		return OriginPatterns.matches(normalizedOrigin, credentialed);
	}

	/** Os padrões que o Spring usa no CORS, já normalizados e estáveis entre requisições. */
	public List<String> corsPatterns() {
		return corsPatterns;
	}

	/** Forma de comparação: esquema/host minúsculos e sem barra final. */
	public static String matchForm(String origin) {
		String clean = origin == null ? "" : origin.trim().toLowerCase(Locale.ROOT);
		while (clean.endsWith("/")) {
			clean = clean.substring(0, clean.length() - 1);
		}
		return clean;
	}
}