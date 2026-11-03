package com.socialcoisa.rede.feed.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Posts carry their attachments inline ({@code media}: address, type, pixel size) in the order of
 * {@code mediaIds}, without removed ones, resolved in one query per page.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PostMediaEmbeddingTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoSpyBean
	private MediaAssetRepository assets;

	@Test
	void postsCarryVisibleMediaInOrderResolvedOncePerPage() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("post_media_embed");
		String sized = asset(author.userId(), "image/png");
		assets.recordDimensions(sized, 1200, 800);
		String unsized = asset(author.userId(), "video/mp4");
		String removed = asset(author.userId(), "image/jpeg");

		JsonNode published = publish(author.token(), List.of(unsized, sized, removed));
		assertThat(ids(published.path("media"))).containsExactly(unsized, sized, removed);
		publish(author.token(), List.of(sized));

		mockMvc.perform(delete("/media/" + removed)
				.header("X-Session-Token", author.token())
				.header("Idempotency-Key", "post-media-embed-delete-" + removed))
			.andExpect(status().isOk());

		JsonNode single = read(get("/feed/posts/" + published.path("id").asText()));
		assertThat(single.path("mediaIds").size()).isEqualTo(3);
		JsonNode media = single.path("media");
		assertThat(ids(media)).containsExactly(unsized, sized);
		assertThat(media.get(0).path("mimeType").asText()).isEqualTo("video/mp4");
		assertThat(media.get(0).path("width").isNull()).isTrue();
		assertThat(media.get(1).path("width").asInt()).isEqualTo(1200);
		assertThat(media.get(1).path("height").asInt()).isEqualTo(800);
		assertThat(media.get(1).path("cdnUrl").asText()).endsWith("/" + sized + "/file");

		clearInvocations(assets);
		JsonNode page = read(get("/users/" + published.path("authorUsername").asText() + "/posts"));
		JsonNode items = page.path("items");
		assertThat(items.size()).isEqualTo(2);
		assertThat(ids(items.get(0).path("media"))).containsExactly(sized);
		assertThat(ids(items.get(1).path("media"))).containsExactly(unsized, sized);
		verify(assets, times(1)).findByIds(any());
	}

	private String asset(String ownerId, String mime) {
		String id = "media_postembed" + UUID.randomUUID().toString().replace("-", "");
		Instant now = Instant.now();
		assets.save(MediaAsset.pending(id, new UserId(ownerId), "file", mime, 10, "POST_IMAGE",
			"uploads/" + id + "/file", "https://enois.exemplo.com/api/storage/uploads/" + id + "/file", now,
			now.plus(1, ChronoUnit.HOURS), MediaStorageProvider.S3));
		return id;
	}

	private JsonNode publish(String token, List<String> mediaIds) throws Exception {
		String body = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "post-media-embed-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA", "type", "INSIGHT",
					"content", "Post com anexos " + UUID.randomUUID(), "tags", List.of(), "mediaIds", mediaIds))))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body);
	}

	private JsonNode read(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private static List<String> ids(JsonNode media) {
		List<String> ids = new java.util.ArrayList<>();
		media.forEach(item -> ids.add(item.path("id").asText()));
		return ids;
	}
}
