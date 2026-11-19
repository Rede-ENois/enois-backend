package com.socialcoisa.replica;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProntidaoReplicaController {

	private final ClienteDados dados;

	ProntidaoReplicaController(ClienteDados dados) {
		this.dados = dados;
	}

	@GetMapping(value = "/readyz", produces = MediaType.TEXT_PLAIN_VALUE)
	ResponseEntity<String> readiness() {
		try {
			return ResponseEntity.ok(dados.versaoPronta() + "\n");
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
		} catch (IOException ignored) {
			// O agente pode ficar sem atribuição entre dois ciclos de reconciliação.
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("indisponível\n");
	}

}
