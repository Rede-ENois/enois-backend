package com.socialcoisa.rede.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.profile.application.OwnProfileView;
import com.socialcoisa.rede.profile.application.UserProfileView;
import com.socialcoisa.rede.profile.domain.UserProfile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Allowlist do que um perfil pode mostrar para terceiros.
 *
 * <p>Campo novo no {@link UserProfileView} quebra este teste de proposito: quem adiciona decide, no
 * mesmo commit, se aquilo e publico. O e-mail e o telefone nunca entram — o e-mail so aparece na
 * projecao do proprio dono ({@link OwnProfileView}, servida apenas em {@code /me}).</p>
 */
class UserProfileViewExposureTests {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String EMAIL = "pessoa.allowlist@exemplo.invalid";
	private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

	/** Exatamente o que a leitura publica pode devolver. */
	private static final Set<String> ALLOWLIST = Set.of(
		"id", "username", "name", "headline", "company", "location", "avatar", "banner", "badge", "bio",
		"bioHtml", "bannerHtml", "skills", "goals", "availability", "portfolio", "externalLinks", "openToWork",
		"isMentor", "connectionsCount", "followersCount", "reputation", "postsCount", "pronouns", "badges", "aka",
		"avatarFrame", "nameTheme", "nameFont", "nameColor", "joinedAt");

	private UserProfile profile() {
		return UserProfile.restore("user-exposure", "pessoa", EMAIL, "Pessoa Teste", "Headline", "Empresa",
			"Cidade", "avatar.png", "banner.png", null, "bio", "<p>bio</p>", "<div>capa</div>",
			List.of("java"), List.of("aprender"), "aberto", "https://portfolio.invalid", List.of("https://link.invalid"),
			true, true, NOW, NOW, true);
	}

	@Test
	void perfilPublicoExpoeExatamenteAAllowlist() {
		JsonNode json = MAPPER.valueToTree(UserProfileView.from(profile()));

		Set<String> campos = new TreeSet<>();
		json.propertyNames().forEach(campos::add);

		assertThat(campos).isEqualTo(new TreeSet<>(ALLOWLIST));
	}

	@Test
	void perfilPublicoNaoLevaEmail() {
		String json = MAPPER.writeValueAsString(UserProfileView.from(profile()));

		assertThat(json).doesNotContain(EMAIL).doesNotContain("email").doesNotContain("telefone").doesNotContain("phone");
	}

	@Test
	void perfilDoProprioDonoContinuaTrazendoOEmail() {
		JsonNode json = MAPPER.valueToTree(OwnProfileView.from(profile()));

		assertThat(json.path("email").asString()).isEqualTo(EMAIL);
	}
}
