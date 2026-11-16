package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StorageObjectState;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.web.ApiException;

@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageDigestTests extends CenarioStorageReplica {

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void digestCompartilhadoResolveObjetoDoNoNasDuasOrdens(boolean inverter) throws Exception {
		No primeiro = no(NodeProfile.NODE_STORAGE);
		No segundo = no(NodeProfile.NODE_STORAGE);
		String corpo = "corpo-sintetico-" + UUID.randomUUID();
		Instant agora = Instant.now();
		StorageObject a = objeto(corpo, primeiro, "application/x-copia-a",
			inverter ? agora : agora.minusSeconds(1), true);
		StorageObject b = objeto(corpo, segundo, "application/x-copia-b",
			inverter ? agora.minusSeconds(1) : agora, true);
		assertThat(a.sha256()).isEqualTo(b.sha256());

		conferirDownload(a, primeiro, corpo);
		conferirDownload(b, segundo, corpo);
	}

	@ParameterizedTest
	@EnumSource(value = StoragePlacement.State.class, names = {"PENDING_REMOTE", "STALE", "GONE"})
	void digestCompartilhadoNaoAutorizaColocacaoSemOk(StoragePlacement.State estado) throws Exception {
		No autorizado = no(NodeProfile.NODE_STORAGE);
		No proibido = no(NodeProfile.NODE_STORAGE);
		String corpo = "estado-sintetico-" + UUID.randomUUID();
		objeto(corpo, autorizado, "application/octet-stream", Instant.now().minusSeconds(1), true);
		StorageObject proprio = objeto(corpo, proibido, "application/octet-stream", Instant.now(), true);
		colocacao(proprio, proibido, estado);
		mvc.perform(download(proprio.sha256(), proibido))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STORAGE_NOT_HELD"))
			.andExpect(request().asyncNotStarted());
	}

	@ParameterizedTest
	@EnumSource(value = StorageObjectState.class, names = {"PENDING", "DELETED"})
	void digestCompartilhadoNaoAutorizaObjetoNaoServivel(StorageObjectState estado) throws Exception {
		No autorizado = no(NodeProfile.NODE_STORAGE);
		No proibido = no(NodeProfile.NODE_STORAGE);
		String corpo = "objeto-sintetico-" + UUID.randomUUID();
		objeto(corpo, autorizado, "application/octet-stream", Instant.now(), true);
		StorageObject proprio = objeto(corpo, proibido, "application/octet-stream", Instant.now(), true);
		jdbc.update("update network_storage_objects set state = ? where id = ?", estado.name(), proprio.id());
		mvc.perform(download(proprio.sha256(), proibido))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("STORAGE_NOT_HELD"))
			.andExpect(request().asyncNotStarted());
	}

	@Test
	void semColocacaoContinua403EDigestDesconhecidoContinua404() throws Exception {
		No dono = no(NodeProfile.NODE_STORAGE);
		No semCopia = no(NodeProfile.NODE_STORAGE);
		StorageObject object = objeto("sem-colocacao-" + UUID.randomUUID(), dono,
			"application/octet-stream", Instant.now(), true);
		mvc.perform(download(object.sha256(), semCopia)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("STORAGE_NOT_HELD")).andExpect(request().asyncNotStarted());
		mvc.perform(download("f".repeat(64), semCopia)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("STORAGE_NOT_FOUND")).andExpect(request().asyncNotStarted());
	}

	@Test
	void donoAutorizadoSemArquivoFalhaNoDispatchNaoNaAutorizacao() throws Exception {
		No dono = no(NodeProfile.NODE_STORAGE);
		StorageObject object = objeto("corpo-ausente-" + UUID.randomUUID(), dono,
			"application/octet-stream", Instant.now(), false);
		var inicio = mvc.perform(download(object.sha256(), dono))
			.andExpect(request().asyncStarted()).andReturn();
		assertThat(inicio.getAsyncResult(5000)).isInstanceOfSatisfying(ApiException.class,
			erro -> assertThat(erro.code()).isEqualTo("STORAGE_REPLICA_ELSEWHERE"));
		mvc.perform(asyncDispatch(inicio)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STORAGE_REPLICA_ELSEWHERE"));
	}

	@Test
	void digestNaoDispensaCredencialNemPerfil() throws Exception {
		No worker = no(NodeProfile.NODE_WORKER);
		StorageObject object = objeto("perfil-sintetico-" + UUID.randomUUID(), worker,
			"application/octet-stream", Instant.now(), true);
		mvc.perform(download(object.sha256(), worker)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NODE_PROFILE_REQUIRED")).andExpect(request().asyncNotStarted());
		mvc.perform(download(object.sha256(), new No(worker.id(), "credencial-invalida-sintetica")))
			.andExpect(status().isUnauthorized()).andExpect(request().asyncNotStarted());
	}

	private void conferirDownload(StorageObject object, No no, String corpo) throws Exception {
		var iniciado = mvc.perform(download(object.sha256(), no))
			.andExpect(status().isOk()).andExpect(request().asyncStarted()).andReturn();
		mvc.perform(asyncDispatch(iniciado))
			.andExpect(status().isOk())
			.andExpect(content().string(corpo))
			.andExpect(content().contentType(object.contentType()))
			.andExpect(header().string("Content-Length", String.valueOf(object.bytes())))
			.andExpect(header().string("ETag", "\"" + object.sha256() + "\""))
			.andExpect(header().string("Cache-Control", "private, no-store"));
		assertThat(iniciado.getResolvedException()).isNull();
	}
}
