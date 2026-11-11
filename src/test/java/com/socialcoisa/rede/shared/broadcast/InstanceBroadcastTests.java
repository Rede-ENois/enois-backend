package com.socialcoisa.rede.shared.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.application.CacheConfig;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Broadcast entre instâncias: o que uma instância emite chega no estado local das outras.
 *
 * <p>Aqui a "outra instância" é simulada pelo que ela deixa no banco — um sinal com
 * {@code origin_instance} diferente do id desta. É o mesmo contrato que duas instâncias reais
 * cumprem: quem emite grava, quem lê entrega no próprio estado. Testar com dois contextos Spring
 * custaria a suíte inteira duas vezes e não provaria nada além disso.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstanceBroadcastTests {

	private static final String OTHER_INSTANCE = "pod-outra-instancia";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private InstanceSignalStore store;

	@Autowired
	private InstanceBroadcaster broadcaster;

	@Autowired
	private InstanceSignalPoller poller;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private JdbcTemplate jdbc;

	/**
	 * A aba aberta nesta instância recebe o aviso de uma escrita atendida em outra. Antes do V276 o
	 * evento morria no processo que o publicou e a aba só via a mudança no próximo carregamento.
	 */
	@Test
	void streamOfThisInstanceReceivesTheSignalOfAnother() throws Exception {
		var user = new TestAuthHelper(mockMvc, objectMapper).authenticate("broadcast_stream_user");
		MvcResult stream = openStream(user.token());

		emitFromOtherInstance(InstanceSignals.USER_EVENT, user.userId(),
			"{\"event\":\"conversation\",\"data\":{\"conversationId\":\"conv_de_outra_instancia\"}}");
		poller.rewind();
		assertThat(poller.runOnce()).isGreaterThanOrEqualTo(1);

		String body = stream.getResponse().getContentAsString();
		assertThat(body).contains("event:conversation").contains("conv_de_outra_instancia");
	}

	/** O sinal de outra instância não vaza para quem não é o alvo. */
	@Test
	void signalOfAnotherInstanceDoesNotReachOtherUsers() throws Exception {
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("broadcast_target_user");
		var outsider = new TestAuthHelper(mockMvc, objectMapper).authenticate("broadcast_outsider_user");
		MvcResult outsiderStream = openStream(outsider.token());

		emitFromOtherInstance(InstanceSignals.USER_EVENT, target.userId(),
			"{\"event\":\"notification\",\"data\":{\"notificationId\":\"n_de_outra\",\"kind\":\"POST_REPOSTED\"}}");
		poller.rewind();
		poller.runOnce();

		assertThat(outsiderStream.getResponse().getContentAsString()).doesNotContain("n_de_outra");
	}

	/**
	 * O cache deixa de servir a versão anterior: a limpeza feita na instância que atendeu a escrita
	 * chega aqui em ~1s, em vez de esperar o TTL de 20s.
	 *
	 * <p>Usa um cache de TTL longo ({@code linkPreviews}, 30 min) de propósito: na suíte o TTL dos
	 * caches dinâmicos é de 1 ms, e com ele a entrada já estaria vencida antes do teste — a
	 * asserção passaria sem o broadcast, e não provaria nada.</p>
	 */
	@Test
	void cacheInvalidationOfAnotherInstanceClearsTheLocalCache() {
		Cache cache = cacheManager.getCache(CacheConfig.LINK_PREVIEWS);
		assertThat(cache).isNotNull();
		cache.put("broadcast:alguem", "versao-antiga");
		assertThat(cache.get("broadcast:alguem")).isNotNull();

		emitFromOtherInstance(InstanceSignals.CACHE_INVALIDATED, CacheConfig.LINK_PREVIEWS, "{}");
		poller.rewind();
		poller.runOnce();

		assertThat(cache.get("broadcast:alguem")).isNull();
	}

	/** Com chave, o sinal remove só aquela entrada — o resto do cache continua servindo. */
	@Test
	void cacheInvalidationWithKeyEvictsOnlyThatEntry() {
		Cache cache = cacheManager.getCache(CacheConfig.LINK_PREVIEWS);
		assertThat(cache).isNotNull();
		cache.put("broadcast:alvo", "versao-antiga");
		cache.put("broadcast:outro", "continua");

		emitFromOtherInstance(InstanceSignals.CACHE_INVALIDATED, CacheConfig.LINK_PREVIEWS + "/broadcast:alvo",
			"{}");
		poller.rewind();
		poller.runOnce();

		assertThat(cache.get("broadcast:alvo")).isNull();
		assertThat(cache.get("broadcast:outro")).isNotNull();
	}

	/**
	 * A escrita local emite o sinal e não se entrega duas vezes: o stream desta instância recebe o
	 * aviso uma vez só, porque a varredura ignora o que a própria instância emitiu.
	 */
	@Test
	void localWriteEmitsTheSignalAndDeliversOnlyOnce() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var sender = auth.authenticate("broadcast_local_sender");
		var recipient = auth.authenticate("broadcast_local_recipient");
		String conversationId = directConversation(sender.token(), recipient.userId());
		MvcResult recipientStream = openStream(recipient.token());

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "broadcast-local-send")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "oi"))))
			.andExpect(status().is2xxSuccessful());

		List<String> origins = jdbc.queryForList("""
			select origin_instance from network_instance_signals
			where signal_type = ? and target = ?
			""", String.class, InstanceSignals.USER_EVENT, recipient.userId());
		assertThat(origins).contains(broadcaster.instanceId());

		// A varredura não reentrega o próprio sinal: o corpo tem o evento uma vez.
		poller.rewind();
		poller.runOnce();
		String body = recipientStream.getResponse().getContentAsString();
		assertThat(countOccurrences(body, "event:conversation")).isEqualTo(1);
	}

	private void emitFromOtherInstance(String type, String target, String payload) {
		store.insert(new InstanceSignal(UUID.randomUUID().toString(), OTHER_INSTANCE, type, target, payload,
			Instant.now()));
	}

	private MvcResult openStream(String token) throws Exception {
		return mockMvc.perform(get("/conversations/stream")
				.header("X-Session-Token", token)
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(request().asyncStarted())
			.andReturn();
	}

	private String directConversation(String token, String otherUserId) throws Exception {
		MvcResult created = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", "broadcast-direct-" + otherUserId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", otherUserId))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();
	}

	private static int countOccurrences(String text, String needle) {
		int count = 0;
		int from = 0;
		while ((from = text.indexOf(needle, from)) >= 0) {
			count++;
			from += needle.length();
		}
		return count;
	}
}
