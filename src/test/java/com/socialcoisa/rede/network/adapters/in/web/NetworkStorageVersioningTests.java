package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.network.application.StorageCatalogService;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestDatabase;

import tools.jackson.databind.ObjectMapper;

/**
 * Versionamento e ciclo de vida (brief §14, V352): política por bucket, versão nova a cada gravação,
 * histórico, restauração, expiração que não serve e varredura.
 *
 * <p>A promessa central do §14 é a do fim do trecho: <b>um nó desconectado não pode restaurar
 * silenciosamente um arquivo já excluído</b>. Por isso toda remoção aqui é <b>lápide</b> — ela existe
 * para a coleta das cópias, e a varredura de expiração nunca apaga linha.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetworkStorageVersioningTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private StorageCatalogService storage;

	private TestAuthHelper auth;
	private String admin;

	@BeforeEach
	void setUp() throws Exception {
		auth = new TestAuthHelper(mockMvc, objectMapper);
		jdbc.update("delete from network_storage_placements");
		jdbc.update("delete from network_storage_objects");
		jdbc.update("delete from network_storage_buckets");
		admin = adminToken();
	}

	/** Bucket sem versionamento continua idempotente: o mesmo upload lê o objeto que já existe. */
	@Test
	void bucket_sem_versionamento_repete_o_mesmo_objeto() {
		String bucket = "sem-versao";
		StorageObject primeiro = storage.beginUpload("u1", bucket, "a.jpg", "image/jpeg");
		StorageObject segundo = storage.beginUpload("u1", bucket, "a.jpg", "image/jpeg");

		assertThat(segundo.id()).isEqualTo(primeiro.id());
		assertThat(segundo.version()).isEqualTo(1L);
	}

	/**
	 * Com versionamento, cada gravação concluída é uma versão nova, e a anterior continua no
	 * histórico. É o que o brief chama de "recuperação de versões anteriores".
	 */
	@Test
	void bucket_versionado_cria_versao_nova_e_preserva_a_anterior() throws Exception {
		String bucket = "fotos-versao";
		policy(bucket, true, null, null);

		StorageObject v1 = armazena(bucket, "a.jpg", "versao um");
		StorageObject v2 = armazena(bucket, "a.jpg", "versao dois");

		assertThat(v2.id()).isNotEqualTo(v1.id());
		assertThat(v2.version()).isEqualTo(2L);
		assertThat(v2.state().name()).isEqualTo("STORED");

		mockMvc.perform(get("/admin/network/storage/objects/versions").header("X-Session-Token", admin)
			.param("bucket", bucket)
			.param("key", "a.jpg"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].version").value(1))
			.andExpect(jsonPath("$[0].current").value(false))
			.andExpect(jsonPath("$[0].supersededBy").value(v2.id()))
			.andExpect(jsonPath("$[1].version").value(2))
			.andExpect(jsonPath("$[1].current").value(true));

		// A chave resolve para a versão corrente: o download por chave devolve o conteúdo novo.
		StorageObject current = storage.requireDownloadable(bucket, "a.jpg");
		assertThat(current.id()).isEqualTo(v2.id());
	}

	/** A versão nova é um objeto próprio: o id da v1 continua apontando para o conteúdo da v1. */
	@Test
	void id_antigo_ainda_aponta_para_o_conteudo_antigo() throws Exception {
		String bucket = "fotos-id";
		policy(bucket, true, null, null);
		StorageObject v1 = armazena(bucket, "b.jpg", "primeiro");
		StorageObject v2 = armazena(bucket, "b.jpg", "segundo");

		assertThat(storage.byId(v1.id())).get().extracting(StorageObject::sha256).isNotNull();
		assertThat(storage.byId(v1.id()).orElseThrow().sha256())
			.isNotEqualTo(storage.byId(v2.id()).orElseThrow().sha256());
	}

	/**
	 * Upload repetido do <b>mesmo</b> conteúdo ainda não confirmado recebe a mesma versão: sem isso,
	 * retry de rede criaria v2, v3, v4 de um arquivo que nunca mudou.
	 */
	@Test
	void retry_do_upload_nao_cria_versao_nova() throws Exception {
		String bucket = "fotos-retry";
		policy(bucket, true, null, null);
		StorageObject v1 = armazena(bucket, "c.jpg", "conteudo");
		// Segunda chamada antes de o corpo da primeira chegar: mesma versão PENDING.
		StorageObject pendente = storage.beginUpload("u1", bucket, "d.jpg", "image/jpeg");
		StorageObject repetido = storage.beginUpload("u1", bucket, "d.jpg", "image/jpeg");

		assertThat(repetido.id()).isEqualTo(pendente.id());
		assertThat(v1.version()).isEqualTo(1L);
	}

	/** Restaurar v1 cria v3 com o conteúdo de v1; não "volta o passado". */
	@Test
	void restaurar_versao_anterior_cria_versao_nova() throws Exception {
		String bucket = "fotos-restaura";
		policy(bucket, true, null, null);
		StorageObject v1 = armazena(bucket, "e.jpg", "versao um");
		StorageObject v2 = armazena(bucket, "e.jpg", "versao dois");

		mockMvc.perform(post("/admin/network/storage/objects/restore").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("bucket", bucket, "key", "e.jpg", "version", 1))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(3))
			.andExpect(jsonPath("$.current").value(true));

		StorageObject current = storage.requireDownloadable(bucket, "e.jpg");
		assertThat(current.version()).isEqualTo(3L);
		// O conteúdo restaurado é o da v1, e a v2 continua no histórico marcada como substituída.
		assertThat(current.sha256()).isEqualTo(v1.sha256());
		assertThat(storage.versions(bucket, "e.jpg")).extracting(StorageObject::id).contains(v1.id(), v2.id(),
			current.id());
	}

	/**
	 * A versão restaurada é <b>baixável</b>: tem corpo de verdade, não só o digest do catálogo.
	 *
	 * <p><b>Antes:</b> o {@code restore} criava a versão nova e a marcava {@code STORED} com o
	 * digest da antiga, mas não gravava cópia nenhuma — nem a réplica 0. O catálogo dizia que a
	 * chave voltou a ter conteúdo; o download respondia {@code 409} sem cópia registrada, e a
	 * varredura acusava divergência num objeto que nunca chegou a um disco.</p>
	 *
	 * <p><b>Agora:</b> a restauração grava a réplica 0 com o corpo da versão antiga, e o digest
	 * sai do que foi efetivamente escrito — o mesmo caminho do upload.</p>
	 */
	@Test
	void versao_restaurada_e_baixavel_com_o_corpo_da_antiga() throws Exception {
		String bucket = "fotos-restaura-corpo";
		policy(bucket, true, null, null);
		StorageObject v1 = armazena(bucket, "h.jpg", "conteudo da versao um");
		armazena(bucket, "h.jpg", "conteudo da versao dois");

		StorageObject restaurada = storage.restore("adm_1", bucket, "h.jpg", 1);

		assertThat(restaurada.sha256()).isEqualTo(v1.sha256());
		MockHttpServletResponse resposta = content(restaurada.id());
		assertThat(resposta.getStatus()).as("a versao restaurada tem corpo").isEqualTo(200);
		assertThat(resposta.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("conteudo da versao um");
	}

	/** Restaurar a versão que já está na frente é erro explícito, não operação vazia. */
	@Test
	void restaurar_a_versao_corrente_e_recusado() throws Exception {
		String bucket = "fotos-corrente";
		policy(bucket, true, null, null);
		armazena(bucket, "f.jpg", "unica");

		mockMvc.perform(post("/admin/network/storage/objects/restore").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("bucket", bucket, "key", "f.jpg", "version", 1))))
			.andExpect(status().isConflict());
	}

	/**
	 * Objeto com prazo vencido <b>não é servido</b>, mesmo com cópia íntegra e antes de a varredura
	 * passar: expiração que só vale quando a varredura roda é um campo na tabela, não expiração.
	 */
	@Test
	void objeto_vencido_nao_e_servido() throws Exception {
		String bucket = "fotos-expira";
		policy(bucket, false, null, 1);
		StorageObject object = armazena(bucket, "g.jpg", "arquivo temporario");
		// Envelhece o prazo: o teste não espera um dia.
		jdbc.update("update network_storage_objects set expires_at = dateadd('SECOND', -60, current_timestamp) "
			+ "where id = ?", object.id());

		MockHttpServletResponse response = content(object.id());
		assertThat(response.getStatus()).isEqualTo(404);
	}

	/** A varredura de expiração marca lápide e devolve o que venceu, sem apagar a linha. */
	@Test
	void varredura_expira_objeto_vencido_com_lapide() throws Exception {
		String bucket = "fotos-varre";
		policy(bucket, false, null, 1);
		StorageObject object = armazena(bucket, "h.jpg", "vai vencer");
		jdbc.update("update network_storage_objects set expires_at = dateadd('SECOND', -60, current_timestamp) "
			+ "where id = ?", object.id());

		mockMvc.perform(post("/admin/network/storage/lifecycle/run").header("X-Session-Token", admin)
			.param("dryRun", "false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expired").value(1));

		java.sql.Timestamp deletado = jdbc.queryForObject("select deleted_at from network_storage_objects where id = ?",
			(rs, row) -> rs.getTimestamp(1), object.id());
		String estado = jdbc.queryForObject("select state from network_storage_objects where id = ?",
			(rs, row) -> rs.getString(1), object.id());
		assertThat(estado).isEqualTo("DELETED");
		assertThat(deletado).isNotNull();
	}

	/** {@code dryRun} é o padrão: relata sem marcar. */
	@Test
	void varredura_em_dry_run_nao_marca_nada() throws Exception {
		String bucket = "fotos-dry";
		policy(bucket, false, null, 1);
		StorageObject object = armazena(bucket, "i.jpg", "ainda vivo");
		jdbc.update("update network_storage_objects set expires_at = dateadd('SECOND', -60, current_timestamp) "
			+ "where id = ?", object.id());

		mockMvc.perform(post("/admin/network/storage/lifecycle/run").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(true))
			.andExpect(jsonPath("$.expired").value(1));

		String estado = jdbc.queryForObject("select state from network_storage_objects where id = ?",
			(rs, row) -> rs.getString(1), object.id());
		assertThat(estado).isEqualTo("STORED");
	}

	/** A política fica gravada e é o que a próxima gravação lê. */
	@Test
	void politica_do_bucket_e_gravada_e_devolvida() throws Exception {
		mockMvc.perform(put("/admin/network/storage/buckets/minhas-politicas/policy").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("versioning", true, "retainSupersededDays", 30,
				"expireAfterDays", 90))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versioning").value(true))
			.andExpect(jsonPath("$.retainSupersededDays").value(30))
			.andExpect(jsonPath("$.configured").value(true));

		mockMvc.perform(get("/admin/network/storage/buckets/minhas-politicas/policy").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expireAfterDays").value(90));
	}

	/** Bucket que ninguém configurou responde os padrões e diz que não há configuração. */
	@Test
	void bucket_sem_politica_devolve_padroes() throws Exception {
		mockMvc.perform(get("/admin/network/storage/buckets/nunca-configurado/policy").header("X-Session-Token", admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versioning").value(false))
			.andExpect(jsonPath("$.configured").value(false));
	}

	/** Retenção num bucket sem versionamento é recusada na borda com 400, não no banco. */
	@Test
	void retencao_sem_versionamento_devolve_400() throws Exception {
		mockMvc.perform(put("/admin/network/storage/buckets/sem-versao-retencao/policy")
			.header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("versioning", false, "retainSupersededDays", 30))))
			.andExpect(status().isBadRequest());
	}

	/** Sem sessão de admin o histórico não é lido: versão antiga é conteúdo de quem gravou. */
	@Test
	void sem_admin_nao_le_historico() throws Exception {
		mockMvc.perform(get("/admin/network/storage/objects/versions").param("bucket", "x").param("key", "y"))
			.andExpect(status().isUnauthorized());
	}

	private void policy(String bucket, boolean versioning, Integer retain, Integer expire) throws Exception {
		mockMvc.perform(put("/admin/network/storage/buckets/" + bucket + "/policy").header("X-Session-Token", admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(policyBody(versioning, retain, expire))))
			.andExpect(status().isOk());
	}

	private static Map<String, Object> policyBody(boolean versioning, Integer retain, Integer expire) {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("versioning", versioning);
		body.put("retainSupersededDays", retain);
		body.put("expireAfterDays", expire);
		return body;
	}

	/**
	 * Grava pelo caminho real: é o que o serviço faz para o Control Plane que recebe o corpo. O nó
	 * que grava é o local — o corpo fica neste disco, que é como a entrega lê depois.
	 */
	private StorageObject armazena(String bucket, String key, String content) {
		StorageObject object = storage.beginUpload("u1", bucket, key, "application/octet-stream");
		storage.store(localNode(), object.id(),
			new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
		return storage.byId(object.id()).orElseThrow();
	}

	/** O nó local: o Control Plane faz o papel da réplica 0 (fase 1). */
	private NetworkNode localNode() {
		return new NetworkNode("nd_local_teste", "local", "owner-local", NodeStatus.ACTIVE,
			Set.of(NodeProfile.NODE_STORAGE), null, null, null, java.time.Instant.now(), 2, 4096, 51200, 50,
			null, null, null, null, null, null, null, 0, null, null);
	}

	private MockHttpServletResponse content(String objectId) throws Exception {
		MvcResult result = mockMvc.perform(get("/network/storage/objects/" + objectId + "/content")).andReturn();
		if (result.getRequest().isAsyncStarted()) {
			result = mockMvc.perform(asyncDispatch(result)).andReturn();
		}
		return result.getResponse();
	}

	private String adminToken() throws Exception {
		var session = auth.authenticate("net_versao_admin_" + System.nanoTime());
		TestDatabase.setAdmin(session.userId());
		return session.token();
	}
}