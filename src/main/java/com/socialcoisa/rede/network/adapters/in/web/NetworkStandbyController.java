package com.socialcoisa.rede.network.adapters.in.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.StandbySyncService;
import com.socialcoisa.rede.network.application.port.out.StandbySnapshotRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * As duas pontas de uma cópia do banco vistas pelo NÓ de reserva (V364).
 *
 * <p>A credencial é a mesma de todas as rotas de nó ({@code X-Node-Id} + {@code X-Node-Key}). O nó
 * só baixa e só confirma a cópia do <b>próprio</b> id: o id da URL e o da credencial têm de ser o
 * mesmo, senão um nó confirmaria cópia de outro — e o failover assumiria um banco que aquele nó não
 * tem.</p>
 */
@RestController
@RequestMapping("/network/standby")
class NetworkStandbyController {

	private final StandbySyncService standby;
	private final com.socialcoisa.rede.network.application.NodeKeyService keys;

	NetworkStandbyController(StandbySyncService standby, com.socialcoisa.rede.network.application.NodeKeyService keys) {
		this.standby = standby;
		this.keys = keys;
	}

	/** O nó recebe o trecho a partir de {@code offset}; o digest do dump inteiro vai no cabeçalho. */
	@GetMapping("/dump")
	ResponseEntity<byte[]> dump(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestParam(value = "offset", required = false) Long offset) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		long from = offset == null ? 0L : offset;
		StandbySyncService.Copy copy = standby.download(node.id(), node.id(), from);
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
			.header("X-Standby-Byte-Start", Integer.toString(copy.byteStart()))
			.header("X-Standby-Total-Bytes", Integer.toString(copy.totalBytes()))
			.header("X-Standby-Sha256", copy.sha256())
			.body(copy.body());
	}

	/**
	 * O nó confirma: "gravei estes bytes, com este digest". A cópia só vira {@code READY} se o
	 * digest bater com o do dump que o servidor mandou — o nó pode estar errado, e é esta rota que
	 * recusa o erro em vez de acreditar nele.
	 */
	@PostMapping("/complete")
	CopyResult complete(@RequestHeader(value = "X-Node-Id", required = false) String nodeId,
			@RequestHeader(value = "X-Node-Key", required = false) String nodeKey,
			@RequestBody(required = false) CompleteRequest request) {
		NetworkNode node = keys.authenticate(nodeId, nodeKey);
		if (request == null) {
			throw new ApiException(400, "STANDBY_COPY_NOT_STARTED", "Corpo ausente: informe bytes e digest da cópia.");
		}
		if (request.nodeId() != null && !node.id().equals(request.nodeId())) {
			throw new ApiException(403, "STANDBY_NODE_MISMATCH",
				"Nó autenticado não confirma a cópia de outro nó.");
		}
		var snapshot = standby.complete(node.id(), node.id(), request.authorityId(), request.bytes(), request.sha256());
		return new CopyResult(snapshot.nodeId(), snapshot.authorityId(), snapshot.state(), snapshot.bytes(),
			snapshot.sha256(), snapshot.failureReason());
	}

	record CompleteRequest(String nodeId, String authorityId, Long bytes, String sha256) {
	}

	record CopyResult(String nodeId, String authorityId, String state, Long bytes, String sha256,
			String reason) {

		static CopyResult of(StandbySnapshotRepository.Snapshot snapshot) {
			return new CopyResult(snapshot.nodeId(), snapshot.authorityId(), snapshot.state(), snapshot.bytes(),
				snapshot.sha256(), snapshot.failureReason());
		}
	}
}