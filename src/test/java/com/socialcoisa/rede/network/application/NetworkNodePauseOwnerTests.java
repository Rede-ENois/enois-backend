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
			180, mock(NetworkNodeResponsibleRepository.class));
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
			mock(NetworkNodeResponsibleRepository.class));

		own.pauseParticipation("nd_1", DONO);

		verify(nodes).updateStatus(eq("nd_1"), eq(NodeStatus.PAUSED), eq(DONO), eq(NOW));
		verify(audit).record(eq(DONO), eq("NODE_PAUSED"), eq("nd_1"), anyString());
		// A chave continua: pausa não é saída da rede.
		verify(keys, never()).revokeAllForNode(anyString(), anyString());
	}

	private static NetworkNode node(String owner, NodeStatus status) {
		return new NetworkNode("nd_1", "no", owner, status, Set.of(), null, null, null, null, 4, 8192, 102400, 100,
			null, null, null, null, (Long) null, 0, null, null);
	}

}