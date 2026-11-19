package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O inventário do disco do nó: o que o catálogo promete contra o que o arquivo diz.
 *
 * <p>O que estes testes seguram, e por quê:</p>
 * <ul>
 * <li><b>órfão é arquivo sem colocação</b> — é o único que a poda pode apagar. Apagar outra coisa
 * perde dado;</li>
 * <li><b>colocado e ausente ≠ colocado e divergente</b>: o primeiro é perda, o segundo é a única
 * cópia possivelmente boa. A poda não toca em nenhum dos dois;</li>
 * <li><b>{@code PENDING_REMOTE} ausente não é perda</b> — a tarefa ainda não rodou. O {@code state}
 * vai no relatório para quem lê distinguir;</li>
 * <li><b>outro nó não consulta este inventário</b>: a rota é autenticada, mas a checagem de dono
 * é no serviço — token válido de outro nó não pode ler o disco desta máquina;</li>
 * <li><b>sem instalação, 409</b> em vez de relatório vazio: vazio diria "nada falta, nada sobra".</li>
 * </ul>
 */
class NodeStorageInventoryTests {

	@TempDir
	Path root;

	private StorageCatalogService storage;
	private NodeBodyStore bodies;
	private AuditTrail audit;
	private NodeStorageInventory inventory;

	@BeforeEach
	void setUp() {
		storage = mock(StorageCatalogService.class);
		bodies = new NodeBodyStore(root.resolve("objects").toString());
		audit = mock(AuditTrail.class);
		inventory = new NodeStorageInventory(storage, bodies, audit);
		provisionado("nd_local");
	}

	/** As credenciais que o runtime grava no diretório do nó: é delas que sai o dono do disco. */
	private void provisionado(String nodeId) {
		try {
			Files.writeString(root.resolve("node.json"), "{\"nodeId\":\"" + nodeId + "\"}");
			Path chave = root.resolve("api-key");
			Files.writeString(chave, "chave-de-teste");
			Files.setPosixFilePermissions(chave, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		}
		catch (Exception erro) {
			throw new IllegalStateException(erro);
		}
	}

	private static NetworkNode node(String id) {
		return new NetworkNode(id, "no-" + id, "user", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			"sudeste", "BR", "1.0.0", Instant.now(), 4, 4096, 102400, 100, null, null, null, null, 4096L, 0, null, null);
	}

	private static String sha(String texto) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(texto.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception impossivel) {
			throw new IllegalStateException(impossivel);
		}
	}

	private static InputStream corpo(String texto) {
		return new ByteArrayInputStream(texto.getBytes(StandardCharsets.UTF_8));
	}

	private void colocado(String objectId, int replica, String sha, String state) {
		when(storage.placementsOn("nd_local")).thenReturn(List.of(new StoragePlacement(objectId, replica,
			"nd_local", 10L, sha, StoragePlacement.State.valueOf(state), Instant.now())));
	}

	@Test
	void corpo_que_confere_e_contado_como_guardado() throws Exception {
		bodies.write("sob_a", corpo("conteudo"));
		colocado("sob_a", 0, sha("conteudo"), "OK");

		NodeStorageInventory.Report r = inventory.inventory(node("nd_local"));

		assertThat(r.held()).hasSize(1);
		assertThat(r.held().get(0).objectId()).isEqualTo("sob_a");
		assertThat(r.missing()).isEmpty();
		assertThat(r.divergent()).isEmpty();
	}

	@Test
	void corpo_colocado_e_ausente_do_disco_e_perda_e_nao_orfao() throws Exception {
		colocado("sob_a", 1, sha("conteudo"), "OK");

		NodeStorageInventory.Report r = inventory.inventory(node("nd_local"));

		assertThat(r.missing()).hasSize(1);
		assertThat(r.missing().get(0).objectId()).isEqualTo("sob_a");
		// Não apareceu como órfão: ele não está no disco, então não há o que podar.
		assertThat(r.orphan()).isEmpty();
	}

	@Test
	void designacao_ainda_nao_puxada_aparece_com_o_estado_para_nao_parecer_perda() {
		colocado("sob_a", 1, sha("conteudo"), "PENDING_REMOTE");

		NodeStorageInventory.Report r = inventory.inventory(node("nd_local"));

		assertThat(r.missing()).hasSize(1);
		// O estado é o que diferencia "a tarefa ainda não rodou" de "perdemos a cópia".
		assertThat(r.missing().get(0).state()).isEqualTo("PENDING_REMOTE");
	}

	@Test
	void corpo_com_digest_diferente_e_divergente_e_nao_guardado() throws Exception {
		bodies.write("sob_a", corpo("CONTEUDO"));
		colocado("sob_a", 0, sha("conteudo"), "OK");

		NodeStorageInventory.Report r = inventory.inventory(node("nd_local"));

		assertThat(r.held()).isEmpty();
		assertThat(r.divergent()).hasSize(1);
		assertThat(r.divergent().get(0).actualSha256()).isEqualTo(sha("CONTEUDO"));
		assertThat(r.divergent().get(0).expectedSha256()).isEqualTo(sha("conteudo"));
	}

	@Test
	void arquivo_sem_colocacao_e_orfao_com_os_bytes_medidos() throws Exception {
		bodies.write("sob_orfao", corpo("lixo de tarefa"));

		NodeStorageInventory.Report r = inventory.inventory(node("nd_local"));

		assertThat(r.orphan()).hasSize(1);
		assertThat(r.orphan().get(0).objectId()).isEqualTo("sob_orfao");
		assertThat(r.orphanBytes()).isEqualTo("lixo de tarefa".length());
	}

	@Test
	void poda_seca_nao_apaga_e_diz_o_que_sairia() throws Exception {
		bodies.write("sob_orfao", corpo("lixo"));

		NodeStorageInventory.Purge p = inventory.purgeOrphans(node("nd_local"), true);

		assertThat(p.removed()).isEmpty();
		assertThat(p.kept()).containsExactly("sob_orfao");
		assertThat(bodies.has("sob_orfao")).isTrue();
		verify(audit, never()).record(anyString(), anyString(), anyString(), anyString());
	}

	@Test
	void poda_apaga_so_o_orfao_e_nunca_o_corpo_colocado() throws Exception {
		bodies.write("sob_orfao", corpo("lixo"));
		bodies.write("sob_ok", corpo("conteudo"));
		colocado("sob_ok", 0, sha("conteudo"), "OK");

		NodeStorageInventory.Purge p = inventory.purgeOrphans(node("nd_local"), false);

		assertThat(p.removed()).containsExactly("sob_orfao");
		assertThat(p.freedBytes()).isEqualTo(4);
		assertThat(bodies.has("sob_orfao")).isFalse();
		// A cópia colocada fica: apagar o que o catálogo promete é perda de dado.
		assertThat(bodies.has("sob_ok")).isTrue();
		verify(audit).record(eq("nd_local"), eq("STORAGE_ORPHANS_PURGED"), eq("nd_local"), anyString());
	}

	@Test
	void poda_nao_apaga_corpo_divergente_porque_e_a_unica_copia_possivelmente_boa() throws Exception {
		bodies.write("sob_trocado", corpo("CONTEUDO"));
		colocado("sob_trocado", 0, sha("conteudo"), "OK");

		NodeStorageInventory.Purge p = inventory.purgeOrphans(node("nd_local"), false);

		// Não é órfão (tem colocação), então não entra nem em `removed` nem em `kept` da poda.
		assertThat(p.removed()).isEmpty();
		assertThat(bodies.has("sob_trocado")).isTrue();
	}

	@Test
	void outro_no_nao_le_o_inventario_deste_disco() throws Exception {
		bodies.write("sob_a", corpo("conteudo"));

		assertThatThrownBy(() -> inventory.inventory(node("nd_outro")))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> {
				ApiException api = (ApiException) erro;
				assertThat(api.status()).isEqualTo(403);
				assertThat(api.code()).isEqualTo("STORAGE_INVENTORY_NOT_OWN");
			});
	}

	@Test
	void api_key_legivel_por_outros_nao_confirma_o_dono_do_disco() {
		// node.json sozinho não prova de quem é o disco. A credencial legível por outro usuário é a
		// que um atacante lê para se passar pelo nó: o processo não assume o disco dela.
		try {
			Files.setPosixFilePermissions(root.resolve("api-key"),
				java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
		}
		catch (Exception erro) {
			throw new IllegalStateException(erro);
		}

		assertThat(inventory.installed()).isFalse();
		assertThatThrownBy(() -> inventory.inventory(node("nd_local")))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> assertThat(((ApiException) erro).code()).isEqualTo("STORAGE_NODE_BODY_DISABLED"));
	}

	@Test
	void sem_instalacao_responde_409_em_vez_de_lista_vazia() {
		NodeStorageInventory semNo = new NodeStorageInventory(storage, null, audit);

		assertThat(semNo.installed()).isFalse();
		assertThatThrownBy(() -> semNo.inventory(node("nd_local")))
			.isInstanceOf(ApiException.class)
			.satisfies(erro -> {
				ApiException api = (ApiException) erro;
				assertThat(api.status()).isEqualTo(409);
				assertThat(api.code()).isEqualTo("STORAGE_NODE_BODY_DISABLED");
			});
	}
}