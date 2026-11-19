package com.socialcoisa.rede.shared.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.info.BuildProperties;

public final class VersaoPublica {

	private VersaoPublica() {
	}

	public static Map<String, Object> de(BuildProperties build) {
		Map<String, Object> resposta = new LinkedHashMap<>();
		Instant instante = build == null ? null : build.getTime();
		resposta.put("version", build == null ? "desconhecida" : build.getVersion());
		resposta.put("artefato", instante == null ? null : instante.toString());
		return resposta;
	}
}
