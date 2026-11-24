package com.socialcoisa.rede.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

@Tag("critico")
class ContentDuplicationGuardTests {

	@Test
	void mesmoConteudoNoMesmoContextoContinuaBloqueado() {
		var guarda = new ContentDuplicationGuard(60);
		guarda.require("conta", "Parabens!", "comment", "post", "resposta");
		assertThatThrownBy(() -> guarda.require("conta", " PARABENS! ", "comment", "post", "resposta"))
			.isInstanceOfSatisfying(ApiException.class, erro -> {
				assertThat(erro.status()).isEqualTo(409);
				assertThat(erro.code()).isEqualTo("DUPLICATE_CONTENT");
			});
	}

	@Test
	void consultaNaoRegistraConteudo() {
		var guarda = new ContentDuplicationGuard(60);
		assertThat(guarda.isDuplicate("conta", "texto", "post")).isFalse();
		assertThat(guarda.isDuplicate("conta", "texto", "post")).isFalse();
		assertThatCode(() -> guarda.require("conta", "texto", "post")).doesNotThrowAnyException();
	}

	@Test
	void contaDestinoRespostaEMidiaDiferentesNaoColidem() {
		var guarda = new ContentDuplicationGuard(60);
		guarda.require("conta", "texto", "comment", "post-a", "raiz", "midia-a");
		assertThat(guarda.isDuplicate("outra", "texto", "comment", "post-a", "raiz", "midia-a")).isFalse();
		assertThat(guarda.isDuplicate("conta", "texto", "comment", "post-b", "raiz", "midia-a")).isFalse();
		assertThat(guarda.isDuplicate("conta", "texto", "comment", "post-a", "outra", "midia-a")).isFalse();
		assertThat(guarda.isDuplicate("conta", "texto", "comment", "post-a", "raiz", "midia-b")).isFalse();
		assertThat(guarda.isDuplicate("conta", "texto", "comment", "post-a", "raiz", "midia-a")).isTrue();
	}

	@Test
	void delimitadoresEmCamposNaoProduzemColisao() {
		var guarda = new ContentDuplicationGuard(60);
		guarda.require("conta", "texto", "a|b", "c");
		assertThat(guarda.isDuplicate("conta", "texto", "a", "b|c")).isFalse();
		guarda.require("conta", "texto", "a", "bc");
		assertThat(guarda.isDuplicate("conta", "texto", "ab", "c")).isFalse();
	}

	@Test
	void janelaDesligadaMantemConsultaEReservaDesligadas() {
		var guarda = new ContentDuplicationGuard(0);
		guarda.require("conta", "texto", "post");
		guarda.require("conta", "texto", "post");
		assertThat(guarda.isDuplicate("conta", "texto", "post")).isFalse();
	}

	@Test
	void espacosEMaiusculasContinuamNormalizados() {
		var guarda = new ContentDuplicationGuard(60);
		guarda.require("conta", "  Texto\n  com   espacos ", "post");
		assertThat(guarda.isDuplicate("conta", "texto com espacos", "post")).isTrue();
	}

	@Test
	void normalizacaoNaoDependeDoIdiomaDaMaquina() {
		Locale anterior = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			var guarda = new ContentDuplicationGuard(60);
			guarda.require("conta", "INSIGHT", "post");
			assertThat(guarda.isDuplicate("conta", "insight", "post")).isTrue();
		} finally {
			Locale.setDefault(anterior);
		}
	}

	@Test
	void apenasUmaRequisicaoConcorrenteReservaOMesmoConteudo() throws Exception {
		var guarda = new ContentDuplicationGuard(60);
		var barreira = new CyclicBarrier(16);
		var tarefas = new ArrayList<Callable<Integer>>();
		for (int i = 0; i < 16; i++) {
			tarefas.add(() -> {
				barreira.await(10, TimeUnit.SECONDS);
				try {
					guarda.require("conta", "texto", "post");
					return 1;
				} catch (ApiException erro) {
					assertThat(erro.code()).isEqualTo("DUPLICATE_CONTENT");
					return 0;
				}
			});
		}
		try (var executor = Executors.newFixedThreadPool(16)) {
			int aceitas = 0;
			for (var resultado : executor.invokeAll(tarefas, 15, TimeUnit.SECONDS)) {
				aceitas += resultado.get();
			}
			assertThat(aceitas).isEqualTo(1);
		}
	}
}
