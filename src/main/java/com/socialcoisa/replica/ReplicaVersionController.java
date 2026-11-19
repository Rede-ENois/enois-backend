package com.socialcoisa.replica;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.shared.web.VersaoPublica;

@RestController
class ReplicaVersionController {

	private final ObjectProvider<BuildProperties> build;

	ReplicaVersionController(ObjectProvider<BuildProperties> build) {
		this.build = build;
	}

	@GetMapping("/version")
	Map<String, Object> version() {
		Map<String, Object> resposta = VersaoPublica.de(build.getIfAvailable());
		resposta.put("status", "UP");
		return resposta;
	}
}
