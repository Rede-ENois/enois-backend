package com.socialcoisa.rede.games.application;

import java.util.List;

/**
 * A página do criador: identidade do Énóis + o que é do Games + os jogos publicados.
 *
 * `following` é de quem está lendo (false sem sessão), e `owner` diz se é a própria página — o front
 * usa isso para trocar o botão de acompanhar por "editar página".
 */
public record DeveloperPageView(String username, String name, String badge, List<String> badges,
		String avatar, String cover, String displayName, String headline, String bio, List<String> links,
		List<GameView> games, long followers, boolean following, boolean owner) {

	/**
	 * `badges` são TODOS os emblemas do criador (o `badge` continua sendo o primeiro, para uma versão
	 * antiga do app) — o nome aparece aqui como em qualquer lugar.
	 */
	public static DeveloperPageView from(DeveloperProfileService.DeveloperPage page, List<String> badges) {
		var profile = page.profile();
		List<String> lista = badges == null ? List.of() : List.copyOf(badges);
		return new DeveloperPageView(page.user().username(), page.user().name(),
			lista.isEmpty() ? null : lista.get(0), lista,
			profile == null ? page.user().avatar() : firstNonBlank(profile.avatar(), page.user().avatar()),
			profile == null ? null : profile.cover(),
			profile == null ? null : profile.displayName(),
			profile == null ? null : profile.headline(),
			profile == null ? null : profile.bio(),
			split(profile == null ? null : profile.links()),
			page.games().stream().map(game -> GameView.of(game, page.user().username())).toList(),
			page.followers(), page.following(), page.owner());
	}

	private static String firstNonBlank(String first, String second) {
		return first == null || first.isBlank() ? second : first;
	}

	private static List<String> split(String value) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return java.util.Arrays.stream(value.split("[,\\n]")).map(String::trim).filter(v -> !v.isEmpty()).toList();
	}
}
