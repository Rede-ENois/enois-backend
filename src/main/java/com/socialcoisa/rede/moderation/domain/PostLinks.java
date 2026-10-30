package com.socialcoisa.rede.moderation.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Os enderecos que o texto publica, ja normalizados para comparacao.
 *
 * O objetivo nao e achar "todos os links do mundo": e achar os que o autor escreveu no texto do
 * post, do comentario ou do perfil, e poder dizer se aquele destino e o mesmo que ja foi julgado
 * antes. Por isso a normalizacao: host em minusculas, sem `www.`, sem barra final, sem fragmento e
 * sem os parametros de rastreio que o proprio site adiciona (o resto da query fica, porque muda o
 * video/artigo).
 */
public final class PostLinks {

	private PostLinks() {
	}

	/** Teto de links considerados por texto: post com 200 links e spam, nao conteudo. */
	public static final int MAX_LINKS = 5;

	private static final Set<String> RASTREADORES = Set.of("utm_source", "utm_medium", "utm_campaign", "utm_term",
			"utm_content", "utm_id", "fbclid", "gclid", "mc_cid", "mc_eid", "igshid", "si", "ref_src", "ref_url");

	public static List<URI> extract(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		Set<String> vistos = new LinkedHashSet<>();
		List<URI> links = new ArrayList<>();
		int i = 0;
		while (i < text.length() && links.size() < MAX_LINKS) {
			int inicio = indexOfScheme(text, i);
			if (inicio < 0) {
				break;
			}
			int fim = inicio;
			while (fim < text.length() && !isBoundary(text.charAt(fim))) {
				fim++;
			}
			URI normalizado = normalize(text.substring(inicio, fim));
			if (normalizado != null && vistos.add(normalizado.toString())) {
				links.add(normalizado);
			}
			i = Math.max(fim, inicio + 1);
		}
		return links;
	}

	/**
	 * Endereco pronto para ser comparado e visitado, ou `null` quando nao e um destino valido.
	 *
	 * So `http`/`https` com host: `javascript:`, `data:`, `file:` e afins nao sao destinos e nao
	 * podem ser visitados por ninguem.
	 */
	public static URI normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String candidato = raw.trim();
		while (!candidato.isEmpty() && ").,;:!?\"'>]}".indexOf(candidato.charAt(candidato.length() - 1)) >= 0) {
			candidato = candidato.substring(0, candidato.length() - 1);
		}
		try {
			URI uri = URI.create(candidato);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) {
				return null;
			}
			String host = uri.getHost();
			if (host == null || host.isBlank() || !host.contains(".")) {
				return null;
			}
			host = host.toLowerCase(Locale.ROOT);
			if (host.startsWith("www.")) {
				host = host.substring(4);
			}
			String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			if (path.length() > 1 && path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
			String query = semRastreadores(uri.getRawQuery());
			int port = uri.getPort();
			boolean portaPadrao = port < 0 || (port == 80 && scheme.equals("http")) || (port == 443 && scheme.equals("https"));
			return new URI(scheme, null, host, portaPadrao ? -1 : port, path, query, null);
		}
		catch (Exception invalido) {
			return null;
		}
	}

	private static String semRastreadores(String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return null;
		}
		StringBuilder mantidos = new StringBuilder();
		for (String par : rawQuery.split("&")) {
			if (par.isBlank()) {
				continue;
			}
			int igual = par.indexOf('=');
			String chave = (igual < 0 ? par : par.substring(0, igual)).toLowerCase(Locale.ROOT);
			if (RASTREADORES.contains(chave)) {
				continue;
			}
			if (!mantidos.isEmpty()) {
				mantidos.append('&');
			}
			mantidos.append(par);
		}
		return mantidos.isEmpty() ? null : mantidos.toString();
	}

	private static int indexOfScheme(String text, int from) {
		int http = text.indexOf("http://", from);
		int https = text.indexOf("https://", from);
		if (http < 0) {
			return https;
		}
		if (https < 0) {
			return http;
		}
		return Math.min(http, https);
	}

	/** O que encerra um endereco no texto: espaco e os separadores que costumam vir depois dele. */
	private static boolean isBoundary(char c) {
		return Character.isWhitespace(c) || c == '<' || c == '>' || c == '"' || c == '\'' || c == '(' || c == ')'
				|| c == '[' || c == ']' || c == '{' || c == '}' || c == ',' || c == ';';
	}

}
