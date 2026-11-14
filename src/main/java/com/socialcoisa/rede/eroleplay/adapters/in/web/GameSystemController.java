package com.socialcoisa.rede.eroleplay.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.eroleplay.application.GameSystemRegistry;
import com.socialcoisa.rede.eroleplay.domain.SheetAttribute;
import com.socialcoisa.rede.eroleplay.domain.SystemAdapter;

/**
 * O catalogo publico dos sistemas de regras registrados.
 *
 * <p>E o que a tela precisa para montar o seletor de sistema na criacao da campanha. Nao expoe
 * regra de sistema comercial: so o que cada adapter registrado declara — identidade, procedencia,
 * licenca e os atributos da ficha. Leitura publica, sem sessao.</p>
 */
@RestController
@RequestMapping("/roleplay/systems")
class GameSystemController {

	private final GameSystemRegistry registry;

	GameSystemController(GameSystemRegistry registry) {
		this.registry = registry;
	}

	@GetMapping
	List<SystemView> systems() {
		return registry.all().stream().map(GameSystemController::view).toList();
	}

	private static SystemView view(SystemAdapter adapter) {
		List<AttributeView> attributes = adapter.attributes().stream().map(GameSystemController::attribute).toList();
		return new SystemView(adapter.id(), adapter.label(), adapter.family().name(), adapter.author(),
			adapter.license(), adapter.officialUrl(), adapter.builtIn(), attributes);
	}

	private static AttributeView attribute(SheetAttribute attribute) {
		return new AttributeView(attribute.key(), attribute.label(), attribute.type().name(), attribute.required(),
			attribute.options());
	}

	record SystemView(String id, String label, String family, String author, String license, String officialUrl,
			boolean builtIn, List<AttributeView> attributes) {
	}

	record AttributeView(String key, String label, String type, boolean required, List<String> options) {
	}
}
