package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeResponsibleRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;
import com.socialcoisa.rede.network.application.port.out.ProvisionTokenRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O dono da pausa, medido no serviço: a tela do participante confere o dono antes de chamar, mas a
 * regra não pode depender de quem chama.
 *
 * <p>O teste HTTP prova que cada rota respeita o dono. O que ele <b>não</b> prova é que a regra
 * está no serviço — com a confirmação no controller, qualquer outro chamador do serviço pausaria o
 * nó de terceiro. Aqui o serviço é chamado direto, com um nó de outro participante.</p>
 */
class NetworkNodePauseOwnerTests {

	private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
	private static final String DONO = "user_dono";
	private static final String OUTRO = "user_outro";

	private NetworkNodeRepository nodes;
	private NodeKeyService keys;
	private NetworkNodeService service;

	@BeforeEach
	void setUp() {
		nodes = mock(NetworkNodeRepository.class);
		keys = mock(NodeKeyService.class);
		service = new NetworkNodeService(nodes, mock(NetworkAuthorityRepository.class),
			mock(ProvisionTokenRepository.class), keys, mock(AuditTrail.class), Clock.fixed(NOW, ZoneOffset.UTC), 30, 600,
			180, mock(NetworkNodeResponsibleRepository.class),
			mock(com.socialcoisa.rede.network.application.port.out.NodeStorageBandwidthRepository.class));
	}

	/** Pausar o nó de outro participante é 404 — e o nó fica como estava. */
	@Test
	void pause_de_no_alheio_nao_muda_nada() {
		when(nodes.findById("nd_1")).thenReturn(java.util.Optional.of(node(OUTRO, NodeStatus.ACTIVE)));

		assertThatThrownBy(() -> service.pauseParticipation("nd_1", DONO)).isInstanceOf(ApiException.class)
			.extracting(error -> ((ApiException) error).status())
			.isEqualTo(404);

		verify(nodes, never()).updateStatus(anyString(), any(), anyString(), any());
		verify(keys, never()).revokeAllForNode(anyString(), anyString());
	}

	/** Retomar o nó de outro participante também não: seria a volta por cima de uma pausa alheia. */
	@Test
	void resume_de_no_alheio_nao_muda_nada() {
		when(nodes.findById("nd_1")).thenReturn(java.util.Optional.of(node(OUTRO, NodeStatus.PAUSED)));

		assertThatThrownBy(() -> service.resumeParticipation("nd_1", DONO)).isInstanceOf(ApiException.class)
			.extracting(error -> ((ApiException) error).status())
			.isEqualTo(404);

		verify(nodes, never()).updateStatus(anyString(), any(), anyString(), any());
	}

	/** Pausa do próprio nó ativo muda o status e audita com o participante como ator. */
	@Test
	void pause_do_proprio_no_pausa_e_audita() {
		when(nodes.findById("nd_1")).thenReturn(java.util.Optional.of(node(DONO, NodeStatus.ACTIVE)));
		AuditTrail audit = mock(AuditTrail.class);
		var own = new NetworkNodeService(nodes, mock(NetworkAuthorityRepository.class),
			mock(ProvisionTokenRepository.class), keys, audit, Clock.fixed(NOW, ZoneOffset.UTC), 30, 600, 180,
			mock(NetworkNodeResponsibleRepository.class),
			mock(com.socialcoisa.rede.network.application.port.out.NodeStorageBandwidthRepository.class));

		own.pauseParticipation("nd_1", DONO);

		verify(nodes).updateStatus(eq("nd_1"), eq(NodeStatus.PAUSED), eq(DONO), eq(NOW));
		verify(audit).record(eq(DONO), eq("NODE_PAUSED"), eq("nd_1"), anyString());
		// A chave continua: pausa não é saída da rede.
		verify(keys, never()).revokeAllForNode(anyString(), anyString());
	}

	/**
	 * O participante não muda a capacidade do nó de terceiro — nem por esta porta. O teste HTTP
	 * prova que a ROTA confere o dono; só o serviço chamado direto prova que a REGRA está nele.
	 */
	@Test
	void participante_nao_muda_capacidade_de_no_alheio() {
		when(nodes.findById("nd_1")).thenReturn(java.util.Optional.of(node(OUTRO, NodeStatus.ACTIVE)));

		assertThatThrownBy(() -> service.participantCapacity("nd_1", DONO, null, null, 1, null))
			.isInstanceOf(ApiException.class)
			.extracting(error -> ((ApiException) error).status())
			.isEqualTo(404);

		verify(nodes, never()).updateContract(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any(),
			any());
	}

	/** O admin continua podendo: a porta dele não passa pela conferência de dono. */
	@Test
	void admin_muda_capacidade_de_qualquer_no() {
		when(nodes.findById("nd_1")).thenReturn(java.util.Optional.of(node(OUTRO, NodeStatus.ACTIVE)));

		service.capacity("nd_1", null, null, 2048, null, "admin_1");

		verify(nodes).updateContract(eq("nd_1"), eq(4), eq(8192), eq(2048), eq(100), any(), eq(NOW));
	}

	private static NetworkNode node(String owner, NodeStatus status) {
		return new NetworkNode("nd_1", "no", owner, status, Set.of(), null, null, null, null, 4, 8192, 102400, 100,
			null, null, null, null, (Long) null, 0, null, null);
	}

	// ------------------------------------------------- §6: banda por tipo, no serviço

	/**
	 * O tipo tem de estar entre os perfis do nó. Limite para tipo que ele não oferece encheria a
	 * config assinada com uma banda que o runtime nunca aplica — assinada, inclusive, o que faria o
	 * número mentiroso parecer autorizado.
	 *
	 * <p>Chamado no SERVIÇO, não na rota: com a checagem só no controller, qualquer outro chamador
	 * gravaria o limite.</p>
	 */
	@Test
	void banda_por_tipo_que_o_no_nao_declara_e_recusada() {
		var lifecycle = mock(NetworkNodeService.class);
		var bandwidth = mock(com.socialcoisa.rede.network.application.port.out.NodeStorageBandwidthRepository.class);
		when(lifecycle.require("nd_1"))
			.thenReturn(storageNode(Set.of(com.socialcoisa.rede.network.domain.NodeProfile.NODE_STORAGE_MEDIA)));
		var participant = participant(lifecycle, bandwidth);

		assertThatThrownBy(() -> participant.storageBandwidth(DONO, "nd_1", "ARCHIVE", 10))
			.isInstanceOf(ApiException.class)
			.extracting(error -> ((ApiException) error).status())
			.isEqualTo(409);

		verify(lifecycle, never()).setStorageBandwidth(anyString(), anyString(), any(), anyInt());
	}

	/** Banda por tipo do nó de OUTRO participante: 404, e nada gravado. */
	@Test
	void banda_por_tipo_de_no_alheio_nao_grava() {
		var lifecycle = mock(NetworkNodeService.class);
		var bandwidth = mock(com.socialcoisa.rede.network.application.port.out.NodeStorageBandwidthRepository.class);
		when(lifecycle.require("nd_1"))
			.thenReturn(storageNodeOf(OUTRO, Set.of(com.socialcoisa.rede.network.domain.NodeProfile.NODE_STORAGE_MEDIA)));
		var participant = participant(lifecycle, bandwidth);

		assertThatThrownBy(() -> participant.storageBandwidth(DONO, "nd_1", "MEDIA", 10))
			.isInstanceOf(ApiException.class)
			.extracting(error -> ((ApiException) error).status())
			.isEqualTo(404);

		verify(lifecycle, never()).setStorageBandwidth(anyString(), anyString(), any(), anyInt());
	}

	/** Tipo declarado e valor nulo: remove a especialização, para o tipo voltar à banda geral. */
	@Test
	void banda_por_tipo_nula_remove() {
		var lifecycle = mock(NetworkNodeService.class);
		var bandwidth = mock(com.socialcoisa.rede.network.application.port.out.NodeStorageBandwidthRepository.class);
		when(lifecycle.require("nd_1"))
			.thenReturn(storageNode(Set.of(com.socialcoisa.rede.network.domain.NodeProfile.NODE_STORAGE_MEDIA)));
		var participant = participant(lifecycle, bandwidth);

		participant.storageBandwidth(DONO, "nd_1", "media", null);

		verify(lifecycle).removeStorageBandwidth(eq("nd_1"), eq(DONO),
			eq(com.socialcoisa.rede.network.domain.StorageNodeKind.MEDIA));
		verify(lifecycle, never()).setStorageBandwidth(anyString(), anyString(), any(), anyInt());
	}

	private NetworkParticipantService participant(NetworkNodeService lifecycle,
			com.socialcoisa.rede.network.application.port.out.NodeStorageBandwidthRepository bandwidth) {
		return new NetworkParticipantService(nodes, mock(com.socialcoisa.rede.network.application.port.out.NetworkTaskRepository.class),
			mock(com.socialcoisa.rede.feed.application.port.out.AuditLogRepository.class), lifecycle,
			bandwidth, novaUptime(), Clock.fixed(NOW, ZoneOffset.UTC), 180);
	}

	private static com.socialcoisa.rede.network.application.NodeUptimeService novaUptime() {
		return new com.socialcoisa.rede.network.application.NodeUptimeService(
			mock(com.socialcoisa.rede.network.application.port.out.NodeUptimeRepository.class),
			Clock.fixed(NOW, ZoneOffset.UTC), 180, 365);
	}

	private static NetworkNode storageNode(Set<com.socialcoisa.rede.network.domain.NodeProfile> profiles) {
		return storageNodeOf(DONO, profiles);
	}

	private static NetworkNode storageNodeOf(String owner,
			Set<com.socialcoisa.rede.network.domain.NodeProfile> profiles) {
		return new NetworkNode("nd_1", "no", owner, NodeStatus.ACTIVE, profiles, null, null, null, NOW, 4, 8192, 102400,
			100, null, null, null, null, (Long) null, 0, null, null);
	}
}
