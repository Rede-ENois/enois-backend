package com.socialcoisa.rede.profile.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.ProfileAppearance;
import com.socialcoisa.rede.profile.domain.ProfileLook;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

class ProfileAppearanceServiceTests {

	private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

	private UserProfileRepository repositoryHolding(UserProfile profile) {
		UserProfileRepository repository = mock(UserProfileRepository.class);
		when(repository.findById(profile.id())).thenReturn(Optional.of(profile));
		when(repository.save(any(UserProfile.class))).thenAnswer(call -> call.getArgument(0));
		return repository;
	}

	@Test
	void socialAndProfessionalAreIndependent() {
		UserProfile profile = TestProfile.of("user_1");
		ProfileAppearanceService service = new ProfileAppearanceService(repositoryHolding(profile), clock);

		service.update("user_1", new ProfileAppearance(
			new ProfileLook("arcade-8bit", "#ff00aa", "mono", "solid", true, "square", 2), ProfileLook.empty()));
		ProfileAppearance afterSocial = service.update("user_1",
			new ProfileAppearance(new ProfileLook("arcade-8bit", "#ff00aa", "mono", "solid", true, "square", 2),
				new ProfileLook("workbench", null, "serif", "clean", false, "rounded", 0)));

		assertThat(afterSocial.social().theme()).isEqualTo("arcade-8bit");
		assertThat(afterSocial.professional().theme()).isEqualTo("workbench");
		// Mexer no profissional não mexeu no social.
		assertThat(afterSocial.social().accent()).isEqualTo("#ff00aa");
		assertThat(afterSocial.professional().font()).isEqualTo("serif");
	}

	@Test
	void emptyRequestClearsBothLooks() {
		UserProfile profile = TestProfile.of("user_1");
		ProfileAppearanceService service = new ProfileAppearanceService(repositoryHolding(profile), clock);
		service.update("user_1", new ProfileAppearance(new ProfileLook("win95", "#123456", "mono", "solid", true, "square", 2),
			new ProfileLook("workbench", null, "serif", "clean", false, "rounded", 0)));

		ProfileAppearance cleared = service.update("user_1", new ProfileAppearance(ProfileLook.empty(), ProfileLook.empty()));

		assertThat(cleared.isEmpty()).isTrue();
		assertThat(profile.profileAppearance().isEmpty()).isTrue();
	}

	@Test
	void normalizesWhatItCanAndRejectsTheRest() {
		ProfileAppearanceService service = new ProfileAppearanceService(
			repositoryHolding(TestProfile.of("user_1")), clock);

		ProfileAppearance saved = service.update("user_1",
			new ProfileAppearance(new ProfileLook(" arcade-8bit ", " #ABCDEF ", " MONO ", " solid ", true, " square ", 1),
				null));

		assertThat(saved.social().theme()).isEqualTo("arcade-8bit");
		assertThat(saved.social().accent()).isEqualTo("#abcdef");
		assertThat(saved.social().font()).isEqualTo("mono");
		assertThat(saved.professional().isEmpty()).isTrue();
	}

	@Test
	void refusesThemeOutsideTheCatalog() {
		ProfileAppearanceService service = new ProfileAppearanceService(
			repositoryHolding(TestProfile.of("user_1")), clock);

		assertThatThrownBy(() -> service.update("user_1",
			new ProfileAppearance(new ProfileLook("tema-inventado", null, null, null, null, null, null), null)))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(400);
				assertThat(error.code()).isEqualTo("INVALID_PROFILE_THEME");
			});
	}

	@Test
	void refusesAccentThatIsNotHex() {
		ProfileAppearanceService service = new ProfileAppearanceService(
			repositoryHolding(TestProfile.of("user_1")), clock);

		assertThatThrownBy(() -> service.update("user_1",
			new ProfileAppearance(new ProfileLook(null, "red; background:url(x)", null, null, null, null, null), null)))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(400);
				assertThat(error.code()).isEqualTo("INVALID_PROFILE_ACCENT");
			});
	}

	@Test
	void refusesUnknownFontHeaderBorderAndDecorationLevel() {
		ProfileAppearanceService service = new ProfileAppearanceService(
			repositoryHolding(TestProfile.of("user_1")), clock);

		assertThatThrownBy(() -> service.update("user_1",
			new ProfileAppearance(new ProfileLook(null, null, "comic-sans", null, null, null, null), null)))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.update("user_1",
			new ProfileAppearance(new ProfileLook(null, null, null, "floating", null, null, null), null)))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.update("user_1",
			new ProfileAppearance(new ProfileLook(null, null, null, null, null, "blob", null), null)))
			.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.update("user_1",
			new ProfileAppearance(new ProfileLook(null, null, null, null, null, null, 9), null)))
			.isInstanceOf(ApiException.class);
	}

	@Test
	void unknownProfileIsNotFound() {
		UserProfileRepository repository = mock(UserProfileRepository.class);
		when(repository.findById("sumiu")).thenReturn(Optional.empty());
		ProfileAppearanceService service = new ProfileAppearanceService(repository, clock);

		assertThatThrownBy(() -> service.update("sumiu", ProfileAppearance.empty()))
			.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
	}

	/** Perfil mínimo, do jeito que o repositório devolve. */
	private static final class TestProfile {

		private TestProfile() {
		}

		static UserProfile of(String id) {
			return UserProfile.restore(id, "mel", "mel@example.com", "Mel", null, null, null, null, null, null,
				null, null, null, List.of(), List.of(), null, null, List.of(), false, false, null, null, true);
		}
	}
}
