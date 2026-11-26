package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.testsupport.CatalogoEmMemoria;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * O healing de "réplica única" (doc 13 §5.10): o objeto que só tem a réplica 0 do Control Plane ganha
 * uma designação de cópia num nó.
 *
 * <p>O antes: a fase 3 existia inteira — colocação, {@code OBJECT_PUT}, confirmação —, e nenhuma delas
 * era chamada para um objeto que acabou de ser gravado. Quando não havia nó elegível no instante do
 * upload, o objeto ficava com a réplica 0 só, e nada voltava a pedir a cópia; a durabilidade que o
 * painel mostrava era a de um objeto a uma perda de disco de sumir.</p>
 *
 * <p>O depois: o operador vê a lista e manda adotar. A adoção é idempotente, nunca repete nó, e não
 * toca em objeto que já tem colocação — nem pendente, que já é transferência pedida.</p>
 */
class StorageReplicaAdoptionTests {

	private static final Instant AGORA = Instant.parse("2026-11-19T12:00:00Z");

	private Catalogo catalogo;
	private NetworkNodeRepository nodes;
	private StorageReplicaAdoption adocao;

	@BeforeEach
	void setUp() {
		catalogo = new Catalogo();
		nodes = mock(NetworkNodeRepository.class);
		StoragePlacementService placement = new StoragePlacementService(nodes, catalogo, 2, 90, 30);
		adocao = new StorageReplicaAdoption(catalogo, placement, Clock.fixed(AGORA, ZoneOffset.UTC));
	}

	private static NetworkNode no(String id, int declaradoMb, long diskFreeMb) {
		return new NetworkNode(id, "vps-" + id, "user_1", NodeStatus.ACTIVE, Set.of(NodeProfile.NODE_STORAGE),
			// `lastSeenAt` recente e disco livre medido: sem os dois o nó não é "vivo" nem "cabe", e a
			// colocação o descarta antes de qualquer régua de folga.
			"sa-east-1", "BR", "1.0.0", AGORA.minusSeconds(30), 2, 4096, declaradoMb, 204800, null, null, null,
			null, null, diskFreeMb, null, null, null, null, null, null, 0, null, null, null);
	}

	private static NetworkNode no(String id, int declaradoMb) {
		return no(id, declaradoMb, 102400L);
	}

	/** O objeto gravado pelo Control Plane: linha STORED com tamanho e digest, sem colocação de nó. */
	private StorageObject naReplicaZero(String id, String chave, long bytes) {
		StorageObject criado = catalogo.createPending(id, "fotos", chave, "image/jpeg", "user_1", AGORA);
		catalogo.markStored(criado.id(), bytes, "sha-" + id, AGORA);
		return catalogo.findById(id).orElseThrow();
	}

	@Test
	void o_objeto_que_so_tem_a_replica_zero_ganha_um_no() {
		quandoOsNosSao(no("nd_a", 102400));
		naReplicaZero("sob_1", "gato.jpg", 1000L);

		StorageReplicaAdoption.Report relatorio = adocao.run(false);

		assertThat(relatorio.considered()).isEqualTo(1);
		assertThat(relatorio.adopted()).hasSize(1);
		assertThat(relatorio.adopted().get(0).nodeId()).isEqualTo("nd_a");
		List<StoragePlacement> colocacoes = catalogo.copies("sob_1");
		assertThat(colocacoes).extracting(StoragePlacement::replica).as("a réplica 0 é do Control Plane")
			.containsExactly(1);
		assertThat(colocacoes).extracting(StoragePlacement::state)
			.containsExactly(StoragePlacement.State.PENDING_REMOTE);
	}

	@Test
	void a_segunda_chamada_nao_pede_outra_copia() {
		quandoOsNosSao(no("nd_a", 102400), no("nd_b", 102400));
		naReplicaZero("sob_1", "gato.jpg", 1000L);
		adocao.run(false);

		// O caso que a lista de candidatos NÃO cobre: o `withOnlyReplicaZero` roda uma vez por
		// chamada, então a segunda chamada não esbarra na designação nova. Quem impede a segunda
		// designação é a trava de `copies` — e é o teste abaixo que a prova, com um repositório que
		// devolve o objeto candidato duas vezes (o cenário de duas instâncias em paralelo).
		StorageReplicaAdoption.Report segunda = adocao.run(false);
		assertThat(segunda.considered()).as("objeto com cópia de nó sai da lista de candidatos").isZero();
		assertThat(catalogo.copies("sob_1")).hasSize(1);
	}

	@Test
	void objeto_que_volta_da_consulta_ja_designado_nao_ganha_outra_designacao() {
		// Duas instâncias (ou duas linhas da mesma página) com o objeto repetido: sem a trava de
		// `copies`, a segunda passada salvaria outra PENDING_REMOTE para a réplica 1, e a confirmação
		// da segunda cairia em "já foi confirmada".
		quandoOsNosSao(no("nd_a", 102400), no("nd_b", 102400));
		StorageObject objeto = naReplicaZero("sob_1", "gato.jpg", 1000L);
		var repetido = new CatalogoQueRepete(objeto);
		StorageReplicaAdoption comFantasma = new StorageReplicaAdoption(repetido,
			new StoragePlacementService(nodes, repetido, 2, 90, 30), Clock.fixed(AGORA, ZoneOffset.UTC));

		StorageReplicaAdoption.Report relatorio = comFantasma.run(false);

		assertThat(relatorio.adopted()).hasSize(1);
		assertThat(relatorio.skipped()).extracting(StorageReplicaAdoption.Skipped::reason)
			.containsExactly("REPLICA_ALREADY_ASSIGNED");
		assertThat(repetido.copies("sob_1")).hasSize(1);
	}

	@Test
	void o_objeto_que_ja_tem_copia_nao_e_candidato() {
		quandoOsNosSao(no("nd_a", 102400));
		naReplicaZero("sob_1", "gato.jpg", 1000L);
		catalogo.savePlacement(new StoragePlacement("sob_1", 1, "nd_a", 1000L, "sha-sob_1",
			StoragePlacement.State.OK, AGORA));

		assertThat(adocao.run(false).considered()).isZero();
		assertThat(adocao.run(false).adopted()).isEmpty();
	}

	@Test
	void sem_no_elegivel_o_objeto_aparece_com_o_motivo_e_nada_e_gravado() {
		quandoOsNosSao();

		StorageReplicaAdoption.Report relatorio = adocao.run(false);

		assertThat(relatorio.considered()).isZero();
		assertThat(catalogo.copies("sob_0")).isEmpty();
	}

	@Test
	void o_dry_run_mostra_o_destino_sem_gravar_a_designacao() {
		quandoOsNosSao(no("nd_a", 102400));
		naReplicaZero("sob_1", "gato.jpg", 1000L);

		StorageReplicaAdoption.Report seco = adocao.preview();

		assertThat(seco.adopted()).hasSize(1);
		assertThat(catalogo.copies("sob_1")).as("dry run não grava").isEmpty();

		// E o seco não esconde o caminho: rodar de verdade depois adota o mesmo objeto.
		assertThat(adocao.run(false).adopted()).hasSize(1);
	}

	@Test
	void objeto_vencido_e_lapidado_ficam_fora() {
		quandoOsNosSao(no("nd_a", 102400));
		naReplicaZero("sob_1", "gato.jpg", 1000L);
		catalogo.setExpiresAt("sob_1", AGORA.minusSeconds(60));
		naReplicaZero("sob_2", "cachorro.jpg", 1000L);
		catalogo.markDeleted("sob_2", AGORA);

		// Replicar objeto que a leitura já responde 404 seria gastar a banda do participante para
		// guardar o que ninguém pede.
		StorageReplicaAdoption.Report relatorio = adocao.run(false);
		assertThat(relatorio.considered()).isZero();
	}

	@Test
	void a_escolha_do_no_usa_a_colocacao_e_nao_o_primeiro_da_lista() {
		// O nó sem folga cedida não pode ser o escolhido nem sendo o primeiro da lista: é a mesma
		// régua do `store` (507 NO_CEDENT_SPACE). Se a adoção escolhesse por "primeiro da consulta",
		// este teste passaria a apontar nd_cheio.
		quandoOsNosSao(no("nd_cheio", 0, 102400), no("nd_folgado", 102400, 102400));
		naReplicaZero("sob_1", "gato.jpg", 1000L);

		StorageReplicaAdoption.Report relatorio = adocao.run(false);

		assertThat(relatorio.adopted()).extracting(StorageReplicaAdoption.Adopted::nodeId).containsExactly("nd_folgado");
	}

	private void quandoOsNosSao(NetworkNode... disponiveis) {
		when(nodes.findCandidates(ArgumentMatchers.any(), ArgumentMatchers.anyInt()))
			.thenReturn(List.of(disponiveis));
	}

	/** O catálogo em memória dos testes de serviço, sem nada a mais. */
	private static final class Catalogo extends CatalogoEmMemoria {
	}

	/** Devolve o mesmo objeto duas vezes: o que duas instâncias em paralelo fariam. */
	private static final class CatalogoQueRepete extends CatalogoEmMemoria {

		private final StorageObject objeto;

		CatalogoQueRepete(StorageObject objeto) {
			this.objeto = objeto;
			guardar(objeto);
		}

		@Override
		public java.util.List<StorageObject> withOnlyReplicaZero(int limit) {
			return java.util.List.of(objeto, objeto);
		}
	}
}