package com.socialcoisa.rede.shared.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Quais rotas servem para DESCOBRIR recursos, e qual identificador cada uma expõe.
 *
 * <p>Enumeração não é volume: é a pessoa (ou o script) pedindo um recurso atrás do outro para
 * descobrir o que existe. Por isso a detecção precisa saber, por rota, <em>qual</em> identificador
 * foi consultado — sem isso só sobra contar requisições, que é o que já existe e o que pune gente
 * honesta com várias abas abertas.</p>
 *
 * <p>A lista é declarativa e vive num lugar só: um controller novo que exponha identificador entra
 * aqui, e não numa regra copiada em cada filtro. As rotas marcadas como {@code shared} são as que
 * a tela chama em toda navegação (contagem de não lidas, stream, heartbeat): elas contam para o
 * teto de volume, mas nunca para o sinal de enumeração — abrir o app dispara várias delas.</p>
 */
public final class EnumerationSurface {

	/** O que a rota expõe: um id opaco, um @ (username) ou nada enumerável. */
	public enum Kind {
		/** Identificador opaco (uuid). Sequência previsível não existe, mas a varredura sim. */
		ID,
		/** @ público. É a superfície clássica: `joao1`, `joao2`, `joao3`. */
		USERNAME,
		/** Rota quente da própria tela: conta volume, nunca enumeração. */
		SHARED
	}

	/**
	 * Uma rota monitorada. {@code pattern} casa com o caminho já normalizado (sem query string);
	 * {@code group} é o grupo de captura que traz o identificador consultado.
	 */
	public record Route(Pattern pattern, Kind kind, String group, String label) {
	}

	private static final List<Route> ROUTES = List.of(
		// --- Descoberta de pessoas: a superfície principal ---
		route("^/users/search$", Kind.USERNAME, null, "users.search"),
		route("^/users/([^/]+)/presence$", Kind.USERNAME, 1, "users.presence"),
		route("^/users/([^/]+)/profile$", Kind.USERNAME, 1, "users.profile"),
		route("^/users/([^/]+)$", Kind.USERNAME, 1, "users.byHandle"),
		route("^/users$", Kind.USERNAME, null, "users.list"),

		// --- Rotas quentes da tela: só volume. Vêm ANTES das rotas com id, porque
		// `/conversations/unread-count` casaria com o padrão de id se viesse depois. ---
		route("^/conversations$", Kind.SHARED, null, "conversations.list"),
		route("^/conversations/unread-count$", Kind.SHARED, null, "conversations.unreadCount"),
		route("^/conversations/stream$", Kind.SHARED, null, "conversations.stream"),
		route("^/me/presence/heartbeat$", Kind.SHARED, null, "presence.heartbeat"),
		route("^/feed$", Kind.SHARED, null, "feed"),
		route("^/actuator/.*$", Kind.SHARED, null, "actuator"),

		// --- Conversas e chamadas: id opaco, mas a varredura é a mesma ---
		route("^/conversations/([^/]+)$", Kind.ID, 1, "conversations.byId"),
		route("^/conversations/([^/]+)/messages$", Kind.ID, 1, "conversations.messages"),
		route("^/calls/([^/]+)$", Kind.ID, 1, "calls.byId"),
		route("^/events/([^/]+)/calls/open$", Kind.ID, 1, "events.callsOpen"),

		// --- Recursos sociais com identificador na URL ---
		route("^/posts/([^/]+)$", Kind.ID, 1, "posts.byId"),
		route("^/posts/([^/]+)/comments$", Kind.ID, 1, "posts.comments"),
		route("^/comments/([^/]+)$", Kind.ID, 1, "comments.byId"),
		route("^/communities/([^/]+)$", Kind.ID, 1, "communities.byId"),
		route("^/jobs/([^/]+)$", Kind.ID, 1, "jobs.byId"),
		route("^/events/([^/]+)$", Kind.ID, 1, "events.byId"),
		route("^/mentors/([^/]+)$", Kind.ID, 1, "mentors.byId"),

		// --- Autenticação: descoberta de conta (existe? já tem conta?) ---
		route("^/auth/login$", Kind.USERNAME, null, "auth.login"),
		route("^/auth/password/forgot$", Kind.USERNAME, null, "auth.forgot"),
		route("^/auth/signup$", Kind.USERNAME, null, "auth.signup"),
		route("^/auth/username-available$", Kind.USERNAME, null, "auth.usernameAvailable"),
		route("^/auth/email-available$", Kind.USERNAME, null, "auth.emailAvailable"),

		// --- Administração: varredura de endpoints é sinal por si ---
		route("^/admin/.*$", Kind.ID, null, "admin.scan"));

	private EnumerationSurface() {
	}

	private static Route route(String regex, Kind kind, Integer group, String label) {
		return new Route(Pattern.compile(regex), kind, group == null ? null : String.valueOf(group), label);
	}

	/**
	 * A rota monitorada para este caminho, ou {@code null} quando a rota não é superfície de
	 * enumeração. O caminho chega sem query string e com o prefixo da API já removido.
	 */
	public static Route match(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}
		String normalized = path.toLowerCase(Locale.ROOT);
		for (Route route : ROUTES) {
			if (route.pattern().matcher(normalized).matches()) {
				return route;
			}
		}
		return null;
	}

	/** O identificador consultado nesta requisição, ou {@code null} quando a rota não o expõe. */
	public static String identifier(Route route, String path) {
		if (route == null || route.group() == null) {
			return null;
		}
		var matcher = route.pattern().matcher(path.toLowerCase(Locale.ROOT));
		if (!matcher.matches()) {
			return null;
		}
		String value = matcher.group(Integer.parseInt(route.group()));
		return value == null || value.isBlank() ? null : value;
	}

	/** Todas as rotas monitoradas (documentação e painel). */
	public static List<Route> routes() {
		return ROUTES;
	}
}
