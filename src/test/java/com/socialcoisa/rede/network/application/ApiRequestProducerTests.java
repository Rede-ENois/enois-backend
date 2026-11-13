package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.TaskStatus;
import com.socialcoisa.rede.network.domain.TaskType;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Fase 5: o produtor de {@code API_REQUEST} — a rota que faltava para o executor existir.
 *
 * <p>O executor do nó e a lista de rotas assinada já estavam entregues; o que não existia era quem
 * enfileirasse a task. Aqui o pedido nasce, com alvo, e chega ao nó de API pelo mesmo caminho das
 * demais.</p>
 *
 * <p>A parte que este teste prende é a <b>allowlist</b>: o produtor escolhe o <b>nome</b> da rota, e o
 * nome só vale se estiver na lista que o nó recebe assinada. Um produtor que aceitasse qualquer
 * nome enfileiraria tasks fadadas ao {@code API_ROUTE_NOT_ALLOWED} — trabalho morto que ocupa a
 * fila até o prazo, e pareceria, no painel, uma rede travada.</p>
 */
@SpringBootTest(properties = {
	"rede.network.config.allowed-routes=catalogo=https://catalogo.exemplo/api;acervo=https://acervo.exemplo/api?ids=1,2"
})
class ApiRequestProducerTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ApiRequestService api;

	@Autowired
	private NetworkTaskService tasks;

	@Autowired
	private NetworkConfigService configs;

	@Autowired
	private NetworkNodeRepository nodes;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from network_tasks");
		jdbc.update("delete from network_state");
		jdbc.update("delete from network_node_keys");
		jdbc.update("delete from network_provision_tokens");
		jdbc.update("delete from network_nodes");
	}

	/**
	 * O pedido nasce com alvo e chega ao nó de API. O payload leva só o nome e o método: destino,
	 * cabeçalho e credencial não vão para a fila.
	 */
	@Test
	void pedidoExplicitoChegaAoNoDeApi() {
		insertActiveNode("nd_api", "NODE_API");

		NetworkTask task = api.request("nd_api", "catalogo", "admin-1");

		assertThat(task.taskType()).isEqualTo(TaskType.API_REQUEST);
		assertThat(task.targetNodeId()).isEqualTo("nd_api");
		assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
		assertThat(task.payload()).contains("\"route\":\"catalogo\"").contains("\"method\":\"GET\"");
		// O destino não viaja: ele é resolvido pelo nó contra a lista assinada.
		assertThat(task.payload()).doesNotContain("https://").doesNotContain("http://");

		assertThat(tasks.dispatchPending()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select assigned_node_id from network_tasks where id = ?", String.class,
			task.id())).isEqualTo("nd_api");
	}

	/**
	 * Rota fora da lista assinada é recusada na hora, e nada nasce na fila.
	 *
	 * <p>Esta é a prova de que o produtor não escolhe o destino: ele escolhe o nome, e o nome é
	 * conferido contra a mesma lista assinada que o nó vai receber.</p>
	 */
	@Test
	void rotaForaDaListaAssinadaERecusada() {
		insertActiveNode("nd_api", "NODE_API");

		assertThatThrownBy(() -> api.request("nd_api", "interna", "admin-1")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "API_ROUTE_NOT_ALLOWED");

		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Long.class)).isEqualTo(0L);
	}

	/** Nó sem o perfil de API é recusado no pedido, com o motivo. */
	@Test
	void pedidoParaNoSemPerfilDeApiERecusado() {
		insertActiveNode("nd_borda", "NODE_EDGE");

		assertThatThrownBy(() -> api.request("nd_borda", "catalogo", "admin-1")).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "NODE_PROFILE_REQUIRED");
	}

	/** Nó de infraestrutura (NODE_LOCAL) executa qualquer tipo — inclusive API_REQUEST. */
	@Test
	void noLocalPodeExecutarRequisicaoDeApi() {
		insertActiveNode("nd_local", "NODE_LOCAL");

		NetworkTask task = api.request("nd_local", "catalogo", "admin-1");

		assertThat(task.taskType()).isEqualTo(TaskType.API_REQUEST);
	}

	/**
	 * A chave é por nó, por rota e por janela: dois nós, ou duas rotas, são trabalhos diferentes.
	 */
	@Test
	void chaveDeIdempotenciaEDistintaPorNoEPorRota() {
		insertActiveNode("nd_a", "NODE_API");
		insertActiveNode("nd_b", "NODE_API");

		NetworkTask a = api.request("nd_a", "catalogo", "admin-1");
		NetworkTask b = api.request("nd_b", "catalogo", "admin-1");
		NetworkTask c = api.request("nd_a", "acervo", "admin-1");

		assertThat(a.id()).isNotEqualTo(b.id());
		assertThat(c.id()).isNotEqualTo(a.id());
		assertThat(a.targetNodeId()).isEqualTo("nd_a");
		assertThat(b.targetNodeId()).isEqualTo("nd_b");
	}

	/** Pedir de novo dentro da janela devolve a mesma tarefa: a chave segura o duplo clique. */
	@Test
	void mesmoPedidoNaMesmaJanelaNaoDuplica() {
		insertActiveNode("nd_a", "NODE_API");

		NetworkTask first = api.request("nd_a", "catalogo", "admin-1");
		NetworkTask second = api.request("nd_a", "catalogo", "admin-1");

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(jdbc.queryForObject("select count(*) from network_tasks", Long.class)).isEqualTo(1L);
	}

	/**
	 * A allowlist do produtor é a mesma lista assinada que o nó recebe — inclusive no caso em que
	 * o destino tem vírgula na query string. A vírgula é caractere legítimo de URL; se o produtor
	 * lesse a propriedade por um parser diferente, ele veria a rota truncada e recusaria um pedido
	 * que o nó executaria.
	 */
	@Test
	void aRotaComVirgulaNaQueryContinuaAutorizada() {
		insertActiveNode("nd_api", "NODE_API");

		assertThat(configs.allowsRoute("acervo")).isTrue();
		assertThat(configs.allowedRouteNames()).containsExactly("acervo", "catalogo");

		NetworkTask task = api.request("nd_api", "acervo", "admin-1");
		assertThat(task.payload()).contains("\"route\":\"acervo\"");
	}

	/**
	 * A lista assinada e a allowlist do produtor não podem divergir: se divergissem, o produtor
	 * aceitaria uma rota que o nó não tem na assinatura (task fadada) ou recusaria uma que o nó
	 * executaria (pedido legítimo barrado).
	 */
	@Test
	void aAllowlistDoProdutorEAMesmaListaQueOEAssinatura() {
		insertActiveNode("nd_api", "NODE_API");

		var view = configs.forNode(nodeOf("nd_api"));

		assertThat(configs.allowedRouteNames()).containsExactly("acervo", "catalogo");
		assertThat(view.allowedRoutes()).extracting(entry -> entry.substring(0, entry.indexOf('=')))
			.containsExactly("acervo", "catalogo");
	}

	private NetworkNode nodeOf(String id) {
		return nodes.findById(id).orElseThrow();
	}

	private void insertActiveNode(String id, String profiles) {
		Instant now = Instant.now();
		jdbc.update("insert into network_nodes (id, display_name, owner_user_id, status, profiles, "
			+ "declared_cpu_cores, declared_memory_mb, declared_disk_mb, declared_bandwidth_mbps, "
			+ "created_at, last_seen_at) values (?, ?, ?, 'ACTIVE', ?, 4, 8192, 51200, 100, ?, ?)", id, id,
			"owner-" + id, profiles, Timestamp.from(now), Timestamp.from(now));
	}
}
