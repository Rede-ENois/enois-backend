package com.socialcoisa.rede.profile.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.profile.domain.ProfileAppearance;
import com.socialcoisa.rede.profile.domain.ProfileLook;

class ProfileAppearanceJsonTests {

	@Test
	void roundTripsBothLooks() {
		ProfileAppearance original = new ProfileAppearance(
			new ProfileLook("arcade-8bit", "#ff00aa", "mono", "solid", true, "square", 2),
			new ProfileLook("workbench", null, "serif", "clean", false, "rounded", 0));

		ProfileAppearance back = ProfileAppearanceJson.decode(ProfileAppearanceJson.encode(original));

		assertThat(back.social()).isEqualTo(original.social());
		assertThat(back.professional()).isEqualTo(original.professional());
	}

	@Test
	void emptyChoiceIsStoredAsAbsence() {
		assertThat(ProfileAppearanceJson.encode(ProfileAppearance.empty())).isNull();
		assertThat(ProfileAppearanceJson.encode(null)).isNull();
		assertThat(ProfileAppearanceJson.decode(null).isEmpty()).isTrue();
		assertThat(ProfileAppearanceJson.decode("   ").isEmpty()).isTrue();
	}

	@Test
	void brokenDocumentFallsBackToTheDefault() {
		assertThat(ProfileAppearanceJson.decode("{isto nao e json").isEmpty()).isTrue();
		assertThat(ProfileAppearanceJson.decode("[]").isEmpty()).isTrue();
	}

	@Test
	void unknownKeysAreIgnoredAndMissingOnesStayNull() {
		ProfileAppearance back = ProfileAppearanceJson.decode(
			"{\"social\":{\"theme\":\"win95\",\"futuro\":\"?\"},\"corporativo\":{\"theme\":\"win95\"}}");

		assertThat(back.social().theme()).isEqualTo("win95");
		assertThat(back.social().accent()).isNull();
		assertThat(back.professional().isEmpty()).isTrue();
	}
}
