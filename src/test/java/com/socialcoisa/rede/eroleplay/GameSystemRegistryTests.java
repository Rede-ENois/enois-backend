package com.socialcoisa.rede.eroleplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.eroleplay.application.GameSystemRegistry;
import com.socialcoisa.rede.eroleplay.domain.D20System;
import com.socialcoisa.rede.eroleplay.domain.GameSystemFamily;
import com.socialcoisa.rede.eroleplay.domain.SystemAdapter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O registro de sistemas de regras: a porta que carrega a regra central do produto.
 *
 * O que este teste tranca:
 *
 * - um sistema registrado e resolvido pelo identificador;
 * - um identificador sem adapter NAO vira sistema generico em silencio (erro proprio);
 * - identificador ausente pede escolha, em vez de assumir um sistema.
 */
class GameSystemRegistryTests {

	private final D20System d20 = new D20System();

	@Test
	void resolveUmSistemaRegistradoPeloIdentificador() {
		GameSystemRegistry registry = new GameSystemRegistry(List.of(d20));
		SystemAdapter resolved = registry.require("d20-generico");
		assertThat(resolved.id()).isEqualTo("d20-generico");
		assertThat(resolved.family()).isEqualTo(GameSystemFamily.D20);
	}

	@Test
	void identificadorSemAdapterNaoViraSistemaGenerico() {
		GameSystemRegistry registry = new GameSystemRegistry(List.of(d20));
		assertThatThrownBy(() -> registry.require("tormenta-oficial"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("nao esta disponivel")
			.extracting(error -> ((ApiException) error).status())
			.isEqualTo(400);
	}

	@Test
	void identificadorVazioPedeEscolha() {
		GameSystemRegistry registry = new GameSystemRegistry(List.of(d20));
		assertThatThrownBy(() -> registry.require("  ")).isInstanceOf(ApiException.class);
	}

	@Test
	void listaTodosOsSistemasRegistrados() {
		GameSystemRegistry registry = new GameSystemRegistry(List.of(d20));
		assertThat(registry.all()).extracting(SystemAdapter::id).containsExactly("d20-generico");
	}

	@Test
	void oSistemaTrazAAutoriaEALicenca() {
		GameSystemRegistry registry = new GameSystemRegistry(List.of(d20));
		SystemAdapter resolved = registry.require(D20System.ID);
		assertThat(resolved.author()).isEqualTo("Éroleplay");
		assertThat(resolved.license()).isEqualTo("conteúdo original do Éroleplay");
	}
}
