package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * DM corporativa (BUSINESS_DIRECT): gate restrito ao initiator, sem afetar JOB/FREELANCE/SERVICE.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BusinessDirectTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void createBusinessDirectRequiresActiveProfessionalProfile() throws Exception {
		var sender = auth.authenticate("biz_v2_no_profile");
		var receiver = auth.authenticate("biz_v2_receiver1");

		mockMvc.perform(post("/conversations/business-direct")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "biz-v2-no-prof")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", receiver.userId()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));
	}

	@Test
	void createBusinessDirectWithActiveProfileSucceeds() throws Exception {
		var sender = auth.authenticate("biz_v2_sender2");
		auth.activateProfessional(sender);
		var receiver = auth.authenticate("biz_v2_receiver2");

		mockMvc.perform(post("/conversations/business-direct")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "biz-v2-ok")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", receiver.userId()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("BUSINESS_DIRECT"))
			.andExpect(jsonPath("$.context").value("PROFESSIONAL"));
	}

	@Test
	void businessDirectAndDirectAreSeparateConversations() throws Exception {
		var a = auth.authenticate("biz_v2_sep_a");
		auth.activateProfessional(a);
		var b = auth.authenticate("biz_v2_sep_b");

		mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "biz-v2-sep-direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("DIRECT"));

		mockMvc.perform(post("/conversations/business-direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "biz-v2-sep-business")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("BUSINESS_DIRECT"));

		mockMvc.perform(get("/conversations")
				.header("X-Session-Token", a.token())
				.param("context", "SOCIAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'DIRECT')]").exists());

		mockMvc.perform(get("/conversations")
				.header("X-Session-Token", a.token())
				.param("context", "PROFESSIONAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'BUSINESS_DIRECT')]").exists());
	}

	@Test
	void receiverWithoutProfessionalProfileCanReplyInBusinessDirect() throws Exception {
		var sender = auth.authenticate("biz_v2_reply_sender");
		auth.activateProfessional(sender);
		var receiver = auth.authenticate("biz_v2_reply_receiver");

		var result = mockMvc.perform(post("/conversations/business-direct")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "biz-v2-reply-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", receiver.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		String conversationId = objectMapper.readTree(
			result.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", receiver.token())
				.header("Idempotency-Key", "biz-v2-reply-send")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "recebi!"))))
			.andExpect(status().isCreated());
	}

	@Test
	void initiatorCannotSendAfterDeactivatingProfile() throws Exception {
		var sender = auth.authenticate("biz_v2_deact_sender");
		auth.activateProfessional(sender);
		var receiver = auth.authenticate("biz_v2_deact_receiver");

		var result = mockMvc.perform(post("/conversations/business-direct")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "biz-v2-deact-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", receiver.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		String conversationId = objectMapper.readTree(
			result.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/me/business-profile/deactivate")
				.header("X-Session-Token", sender.token()))
			.andExpect(status().isOk());

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "biz-v2-deact-send")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "oi"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));
	}

	@Test
	void sendInSocialConversationWorksWithoutProfessionalProfile() throws Exception {
		var a = auth.authenticate("biz_v2_social_a");
		var b = auth.authenticate("biz_v2_social_b");

		var result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "biz-v2-social-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		String conversationId = objectMapper.readTree(
			result.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "biz-v2-social-send")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "oi"))))
			.andExpect(status().isCreated());
	}

	@Test
	void professionalConversationShowsCorporateIdentity() throws Exception {
		var sender = auth.authenticate("biz_v2_identity_sender");
		auth.activateProfessional(sender);
		var receiver = auth.authenticate("biz_v2_identity_receiver");

		mockMvc.perform(post("/conversations/business-direct")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "biz-v2-identity-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", receiver.userId()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.identities").isNotEmpty())
			.andExpect(jsonPath("$.identities['" + sender.userId() + "'].identity").value("CORPORATE"));
	}
}
