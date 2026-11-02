package com.socialcoisa.rede.gifs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.gifs.application.port.out.GifProvider;
import com.socialcoisa.rede.shared.web.ApiException;

class GifSearchServiceTests {

	@Test
	void aFailingProviderHandsTheSearchToTheNextOne() {
		var service = new GifSearchService(List.of(failing("tenor"), working("giphy")));

		GifSearchResult result = service.search("oi", 1);

		assertThat(result.available()).isTrue();
		assertThat(result.provider()).isEqualTo("giphy");
	}

	@Test
	void theErrorReachesTheScreenOnlyWhenEveryProviderFails() {
		var service = new GifSearchService(List.of(failing("tenor"), failing("giphy")));

		assertThatThrownBy(() -> service.search("oi", 1)).isInstanceOf(ApiException.class);
	}

	@Test
	void withoutKeysTheSearchIsUnavailableNotAnError() {
		var service = new GifSearchService(List.of(provider("tenor", false, null)));

		assertThat(service.search("oi", 1).available()).isFalse();
	}

	private static GifProvider failing(String name) {
		return provider(name, true, new ApiException(502, "GIF_PROVIDER_ERROR", "fora"));
	}

	private static GifProvider working(String name) {
		return provider(name, true, null);
	}

	private static GifProvider provider(String name, boolean available, RuntimeException failure) {
		return new GifProvider() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public boolean available() {
				return available;
			}

			@Override
			public List<GifItem> search(String query, int limit) {
				if (failure != null) {
					throw failure;
				}
				return List.of();
			}
		};
	}
}
