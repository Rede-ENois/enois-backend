package com.socialcoisa.rede.eroleplay.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.eroleplay.domain.SystemAdapter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O REGISTRO de sistemas de regras: a porta pela qual a campanha resolve o sistema que escolheu.
 *
 * <p>Aqui mora a regra central do Éroleplay. A campanha guarda apenas o IDENTIFICADOR do sistema,
 * nunca as regras; quem carrega a semântica é o adapter. Um identificador que não tem adapter
 * registrado não vira um sistema genérico em silêncio: responde com erro explícito, porque aceitar
 * um sistema que não existe faria a mesa descobrir o problema só no meio da sessão.</p>
 *
 * <p>Os adapters entram por injeção do Spring: registrar um sistema novo é publicar um bean, não
 * editar este registro.</p>
 */
@Service
public class GameSystemRegistry {

	private final Map<String, SystemAdapter> byId;

	public GameSystemRegistry(List<SystemAdapter> adapters) {
		Map<String, SystemAdapter> index = new LinkedHashMap<>();
		for (SystemAdapter adapter : adapters) {
			index.put(adapter.id(), adapter);
		}
		this.byId = Map.copyOf(index);
	}

	/** Todos os sistemas registrados, na ordem em que foram injetados. */
	public List<SystemAdapter> all() {
		return List.copyOf(byId.values());
	}

	/**
	 * Resolve o sistema pelo identificador.
	 *
	 * @throws ApiException 400 {@code GAME_SYSTEM_NOT_SUPPORTED} quando não há adapter registrado
	 */
	public SystemAdapter require(String systemId) {
		if (!StringUtils.hasText(systemId)) {
			throw new ApiException(400, "GAME_SYSTEM_REQUIRED", "Escolha um sistema de regras para a campanha.");
		}
		SystemAdapter adapter = byId.get(systemId.trim());
		if (adapter == null) {
			throw new ApiException(400, "GAME_SYSTEM_NOT_SUPPORTED",
				"O sistema de regras informado nao esta disponivel.");
		}
		return adapter;
	}
}
