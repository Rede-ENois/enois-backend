package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkRegionRepository;
import com.socialcoisa.rede.network.domain.NetworkRegions;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Recuperação regional: a declaração humana de que uma região saiu de operação — e de que voltou.
 *
 * <p>O critério do plano (docs/rede-distribuida/07-plano-incremental.md, fase 6) é "região afetada
 * sai; nós de outras regiões atendem o que podem". A primeira metade não existia: a região era só
 * afinidade de ordenação (ADR-012), e numa queda regional a rede continuava designando tarefa para
 * os nós caídos — tarefa presa até o claim vencer, devolvida, e designada DE NOVO para o mesmo nó
 * da região morta, em loop, até a tarefa morrer de tentativas.</p>
 *
 * <p>A declaração é decisão humana com motivo obrigatório, pelo mesmo raciocínio do modo
 * operacional (ADR-009): nenhum número que o Control Plane meça distingue "região em desastre" de
 * "região com a rede lenta", e declarar é o que deixa o incidente auditado — quem declarou, por quê
 * e desde quando. A volta também é humana: readmitir por relógio seria a automação desfazendo
 * decisão de incidente.</p>
 *
 * <p>Declarada a região, quatro portas fecham para os nós dela: o despacho não escolhe (a escolha
 * perde o candidato), a capacidade do modo não conta a vaga (a razão fila/vagas vê o desastre),
 * o heartbeat responde {@code acceptTasks=false} (o nó para de pedir, drenando sem 403 — é o que
 * mantém o canal de reporte aberto), e as tarefas presas aos nós da região voltam à fila na hora
 * (a drenagem regional, que não espera o claim TTL).</p>
 */
@Service
public class NetworkRegionService {

	private static final Logger LOG = LoggerFactory.getLogger(NetworkRegionService.class);

	/** O motivo entra no metadata JSON da auditoria; varchar(200) no banco. */
	static final int MAX_REASON = 200;

	private final NetworkRegionRepository regions;
	private final AuditTrail audit;
	private final Clock clock;

	NetworkRegionService(NetworkRegionRepository regions, AuditTrail audit, Clock clock) {
		this.regions = regions;
		this.audit = audit;
		this.clock = clock;
	}

	/** As regiões fora de operação agora, normalizadas, em ordem de declaração. */
	@Transactional(readOnly = true)
	public List<String> outOfService() {
		return regions.outOfService();
	}

	/**
	 * A região normalizada está fora de operação? Um nó ou tarefa SEM região nunca está: região nula
	 * empata em 0 na afinidade e segue elegível — evacuar quem não declarou região seria tirar a
	 * rede toda por uma grafia que ninguém comparou.
	 */
	public boolean isOutOfService(String region) {
		if (region == null || region.isBlank()) {
			return false;
		}
		return regions.outOfService().contains(NetworkRegions.normalize(region));
	}

	/** O registro do painel, se a região estiver fora de operação. */
	@Transactional(readOnly = true)
	public Optional<NetworkRegionRepository.RegionRecord> find(String region) {
		return regions.find(NetworkRegions.normalize(region));
	}

	/**
	 * Declara a região fora de operação. O motivo é obrigatório e vai para a auditoria com o ator:
	 * "ativei a evacuação para ver o que acontece" sem registro é incidente sem história.
	 */
	@Transactional
	public void declare(String actorId, String region, String reason) {
		String normalized = NetworkRegions.normalize(region);
		if (normalized.isEmpty()) {
			throw new ApiException(400, "NETWORK_REGION_REQUIRED", "Informe a região a tirar de operação.");
		}
		if (normalized.length() > 64) {
			throw new ApiException(400, "NETWORK_REGION_TOO_LONG",
				"A região tem de caber em 64 caracteres — é o que a coluna de nó e de tarefa guarda.");
		}
		if (reason == null || reason.isBlank()) {
			throw new ApiException(400, "NETWORK_REGION_REASON_REQUIRED",
				"Declare o motivo da evacuação — tirar uma região do ar é decisão de incidente.");
		}
		String trimmedReason = reason.trim();
		if (trimmedReason.length() > MAX_REASON) {
			throw new ApiException(400, "NETWORK_REGION_REASON_TOO_LONG",
				"O motivo tem de caber em " + MAX_REASON + " caracteres.");
		}
		// Declarar de novo é no-op na tabela, mas o registro na auditoria é o que conta a segunda
		// declaração — e o painel mostra `since` da PRIMEIRA: redeclarar não reinicia o incidente.
		regions.declare(normalized, actorId, trimmedReason, clock.instant());
		audit.record(actorId, "NETWORK_REGION_OUT_OF_SERVICE", normalized,
			"{\"reason\":\"" + escape(trimmedReason) + "\"}");
		LOG.warn("Região {} fora de operação (por {}): {}", normalized, actorId, trimmedReason);
	}

	/** Devolve a região à operação. Não estava fora? 404 confesso — a tela não acha que liberou algo. */
	@Transactional
	public void clear(String actorId, String region) {
		String normalized = NetworkRegions.normalize(region);
		if (!regions.clear(normalized)) {
			throw new ApiException(404, "NETWORK_REGION_NOT_DECLARED",
				"Esta região não está fora de operação.");
		}
		audit.record(actorId, "NETWORK_REGION_BACK_IN_SERVICE", normalized, "{}");
		LOG.info("Região {} de volta à operação (por {})", normalized, actorId);
	}

	/** Motivo entra em metadata JSON: aspas e barra do texto humano não podem quebrar o JSON. */
	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
