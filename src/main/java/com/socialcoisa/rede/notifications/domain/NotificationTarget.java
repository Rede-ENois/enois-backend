package com.socialcoisa.rede.notifications.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a notification points at, read from its {@code href}: a community, a post (whose community
 * is its place), a group (by slug) or an event. The formats are the ones the modules write:
 * {@code /comunidades/{id}}, {@code /api/feed/posts/{id}[?reacoes=1]}, {@code /grupos/{slug}} and
 * {@code /eventos?evento={id}}. Any other href has no target.
 */
public record NotificationTarget(Type type, String key) {

	public enum Type {
		COMMUNITY, POST, GROUP, EVENT
	}

	private static final Pattern COMMUNITY = Pattern.compile("^/comunidades/([^/?#]+)");
	private static final Pattern POST = Pattern.compile("^/api/feed/posts/([^/?#]+)");
	private static final Pattern GROUP = Pattern.compile("^/grupos/([^/?#]+)");
	private static final Pattern EVENT = Pattern.compile("^/eventos\\?(?:[^#]*&)?evento=([^&#]+)");

	public static Optional<NotificationTarget> of(String href) {
		if (href == null) {
			return Optional.empty();
		}
		return match(COMMUNITY, Type.COMMUNITY, href)
			.or(() -> match(POST, Type.POST, href))
			.or(() -> match(GROUP, Type.GROUP, href))
			.or(() -> match(EVENT, Type.EVENT, href));
	}

	private static Optional<NotificationTarget> match(Pattern pattern, Type type, String href) {
		Matcher matcher = pattern.matcher(href);
		return matcher.find() ? Optional.of(new NotificationTarget(type, matcher.group(1))) : Optional.empty();
	}
}
