package com.socialcoisa.rede.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Public listings and pages identify people by their @, not by the internal account id. These views
 * carry the @ (or nothing about the person) and the front never read the id, so it is not sent.
 */
class PublicViewIdentifierTests {

	private static final Set<String> ACCOUNT_ID_FIELDS = Set.of("userId", "sellerId", "requesterId", "creatorId");

	@ParameterizedTest
	@ValueSource(strings = {
		"com.socialcoisa.rede.freelances.application.FreelanceView",
		"com.socialcoisa.rede.games.application.DeveloperPageView",
		"com.socialcoisa.rede.communityprojects.application.CommunityProjectView",
		"com.socialcoisa.rede.marketplace.adapters.in.web.MarketplaceController$ListingView",
		"com.socialcoisa.rede.services.adapters.in.web.ServicesController$RequestView",
		"com.socialcoisa.rede.cosmetics.adapters.in.web.CommunityCosmeticsController$CosmeticView" })
	void publicViewDoesNotCarryTheAccountId(String viewClass) throws Exception {
		List<String> fields = Arrays.stream(Class.forName(viewClass).getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName)
			.toList();

		assertThat(fields).isNotEmpty().doesNotContainAnyElementsOf(ACCOUNT_ID_FIELDS);
	}
}
