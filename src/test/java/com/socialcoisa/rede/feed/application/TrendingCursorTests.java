package com.socialcoisa.rede.feed.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.ApiException;

@ExtendWith(MockitoExtension.class)
class TrendingCursorTests {

	@Mock private PostRepository posts;
	@InjectMocks private GetFeedService feed;

	@Test
	void emptyRankingEndsWithoutACursor() {
		FeedPage page = feed.getFeed(new GetFeedQuery(new UserId("synthetic_reader"),
			java.util.Optional.empty(), 20, FeedTab.TRENDING));
		assertThat(page.items()).isEmpty();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void preservesTheWindowAndAllRankingKeys() {
		Instant until = Instant.parse("2030-01-01T12:00:00Z");
		TrendingCursor cursor = new TrendingCursor(until, 25, until.minusSeconds(10), "synthetic_post");
		assertThat(TrendingCursor.decode(cursor.encode())).isEqualTo(cursor);
		assertThat(TrendingCursor.decode(null)).isNull();
		assertThat(TrendingCursor.decode(" ")).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "not-a-cursor!", "2030-01-01T12:00:00Z" })
	void rejectsMalformedAndLegacyTimestampCursors(String value) {
		assertInvalid(value);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"2|2030-01-01T12:00:00Z|25|2030-01-01T11:00:00Z|post",
		"1|invalid|25|2030-01-01T11:00:00Z|post",
		"1|2030-01-01T12:00:00Z|-1|2030-01-01T11:00:00Z|post",
		"1|2030-01-01T12:00:00Z|25|2030-01-01T13:00:00Z|post",
		"1|2030-01-01T12:00:00Z|25|2029-12-30T11:00:00Z|post",
		"1|2030-01-01T12:00:00Z|25|2030-01-01T11:00:00Z|"
	})
	void rejectsInvalidRankingKeys(String value) {
		assertInvalid(Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
	}

	private void assertInvalid(String value) {
		assertThatThrownBy(() -> TrendingCursor.decode(value)).isInstanceOfSatisfying(ApiException.class, error -> {
			assertThat(error.status()).isEqualTo(400);
			assertThat(error.code()).isEqualTo("INVALID_CURSOR");
		});
	}
}
