package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.web.ApiException;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageConfirmationTests extends CenarioStorageReplica {

	@Autowired ObjectMapper mapper;

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "nao-e-digest", "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg",
		"000000000000000000000000000000000000000000000000000000000000000",
		"00000000000000000000000000000000000000000000000000000000000000000"})
	void digestInvalidoNoHttpNaoConsomeDesignacao(String digest) throws Exception {
		No replica = no(NodeProfile.NODE_STORAGE);
		StorageObject object = designado(replica);
		StoragePlacement antes = catalog.placementOn(object.id(), replica.id()).orElseThrow();
		Map<String, Object> body = new HashMap<>();
		body.put("bytes", object.bytes());
		body.put("sha256", digest);
		var response = mvc.perform(confirmacao(object, replica, mapper.writeValueAsString(body))).andReturn();
		assertAll(
			() -> assertThat(response.getResponse().getStatus()).isEqualTo(400),
			() -> assertThat(mapper.readTree(response.getResponse().getContentAsString()).get("code").asString())
				.isEqualTo("STORAGE_DIGEST_INVALID"),
			() -> assertThat(catalog.placementOn(object.id(), replica.id())).contains(antes),
			() -> assertThat(catalog.findById(object.id())).contains(object),
			() -> assertThat(eventos(object)).isZero());
		confirmarDepoisDaRecusa(object, replica);
	}

	@Test
	void digestAusenteNoHttpNaoConsomeDesignacao() throws Exception {
		No replica = no(NodeProfile.NODE_STORAGE);
		StorageObject object = designado(replica);
		StoragePlacement antes = catalog.placementOn(object.id(), replica.id()).orElseThrow();
		mvc.perform(confirmacao(object, replica, "{\"bytes\":" + object.bytes() + "}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STORAGE_DIGEST_INVALID"));
		assertThat(catalog.placementOn(object.id(), replica.id())).contains(antes);
		assertThat(eventos(object)).isZero();
		confirmarDepoisDaRecusa(object, replica);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"nao-e-digest", "G"})
	void digestInvalidoNoServicoNaoAlteraColocacaoNemAuditoria(String digest) throws Exception {
		No replica = no(NodeProfile.NODE_STORAGE);
		StorageObject object = designado(replica);
		StoragePlacement antes = catalog.placementOn(object.id(), replica.id()).orElseThrow();
		Throwable erro = catchThrowable(() -> storage.confirmReplica(nodes.findById(replica.id()).orElseThrow(),
			object.id(), object.bytes(), digest));
		assertAll(
			() -> assertThat(erro).isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.status()).isEqualTo(400);
				assertThat(e.code()).isEqualTo("STORAGE_DIGEST_INVALID");
			}),
			() -> assertThat(catalog.placementOn(object.id(), replica.id())).contains(antes),
			() -> assertThat(catalog.findById(object.id())).contains(object),
			() -> assertThat(eventos(object)).isZero());
	}

	@Test
	void confirmacaoHexMaiusculaERetryPreservamContrato() throws Exception {
		No replica = no(NodeProfile.NODE_STORAGE);
		StorageObject object = designado(replica);
		String body = mapper.writeValueAsString(Map.of("bytes", object.bytes(),
			"sha256", object.sha256().toUpperCase(java.util.Locale.ROOT)));
		mvc.perform(confirmacao(object, replica, body)).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("OK"));
		StoragePlacement confirmed = catalog.placementOn(object.id(), replica.id()).orElseThrow();
		assertThat(confirmed.sha256()).isEqualTo(object.sha256());
		mvc.perform(confirmacao(object, replica, body)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STORAGE_REPLICA_ALREADY_CONFIRMED"));
		assertThat(catalog.placementOn(object.id(), replica.id())).contains(confirmed);
		assertThat(eventos(object)).isEqualTo(1);
	}

	@Test
	void digestValidoDivergenteAindaConsomeDesignacaoComoStale() throws Exception {
		No replica = no(NodeProfile.NODE_STORAGE);
		StorageObject object = designado(replica);
		mvc.perform(confirmacao(object, replica, mapper.writeValueAsString(
			Map.of("bytes", object.bytes(), "sha256", "0".repeat(64)))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORAGE_REPLICA_MISMATCH"));
		StoragePlacement stale = catalog.placementOn(object.id(), replica.id()).orElseThrow();
		assertThat(stale.state()).isEqualTo(StoragePlacement.State.STALE);
		assertThat(eventos(object)).isEqualTo(1);
		mvc.perform(confirmacao(object, replica, mapper.writeValueAsString(
			Map.of("bytes", object.bytes(), "sha256", object.sha256()))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORAGE_REPLICA_ALREADY_CONFIRMED"));
		assertThat(catalog.placementOn(object.id(), replica.id())).contains(stale);
		assertThat(eventos(object)).isEqualTo(1);
	}

	@Test
	void confirmacaoNaoDispensaCredencialPerfilNemDesignacao() throws Exception {
		No replica = no(NodeProfile.NODE_STORAGE);
		No intruso = no(NodeProfile.NODE_STORAGE);
		No worker = no(NodeProfile.NODE_WORKER);
		StorageObject object = designado(replica);
		String body = mapper.writeValueAsString(Map.of("bytes", object.bytes(), "sha256", object.sha256()));
		mvc.perform(confirmacao(object, intruso, body)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STORAGE_REPLICA_NOT_DESIGNATED"));
		mvc.perform(confirmacao(object, worker, body)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PROFILE_REQUIRED"));
		mvc.perform(confirmacao(object, new No(replica.id(), "credencial-invalida-sintetica"), body))
			.andExpect(status().isUnauthorized());
		mvc.perform(confirmacao(object, replica, "")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("STORAGE_REPLICA_BODY_MISSING"));
		assertThat(catalog.placementOn(object.id(), replica.id()).orElseThrow().state())
			.isEqualTo(StoragePlacement.State.PENDING_REMOTE);
		assertThat(eventos(object)).isZero();
	}

	private StorageObject designado(No replica) throws Exception {
		StorageObject object = objeto("confirmacao-sintetica-" + UUID.randomUUID(), replica,
			"application/octet-stream", Instant.now(), false);
		colocacao(object, replica, StoragePlacement.State.PENDING_REMOTE);
		return object;
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirmacao(
			StorageObject object, No replica, String body) {
		return post("/network/storage/objects/" + object.id() + "/replica")
			.header("X-Node-Id", replica.id()).header("X-Node-Key", replica.chave())
			.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private long eventos(StorageObject object) {
		return jdbc.queryForObject("select count(*) from audit_logs where target_id = ?", Long.class, object.id());
	}

	private void confirmarDepoisDaRecusa(StorageObject object, No replica) throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/network/storage/placements")
			.header("X-Node-Id", replica.id()).header("X-Node-Key", replica.chave()))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("PENDING_REMOTE"));
		mvc.perform(confirmacao(object, replica, mapper.writeValueAsString(
			Map.of("bytes", object.bytes(), "sha256", object.sha256()))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OK"));
		assertThat(catalog.placementOn(object.id(), replica.id()).orElseThrow().state())
			.isEqualTo(StoragePlacement.State.OK);
		assertThat(eventos(object)).isEqualTo(1);
	}
}
