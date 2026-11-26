package com.socialcoisa.rede.leitura.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.leitura.application.port.out.LeituraCache;
import com.socialcoisa.rede.leitura.application.port.out.LeitorDeTexto;
import com.socialcoisa.rede.leitura.domain.Intencao;
import com.socialcoisa.rede.leitura.domain.LeituraTexto;

/**
 * A leitura de intenção: o que vem do modelo é validado, nunca confiável; o cache paga a inferência
 * uma vez por texto; e sem modelo a leitura degrada (marcada), sem quebrar quem consome.
 */
class LeituraDeTextoTests {

	@Test
	void leituraDoModeloValidaTemasIntencaoEResumo() {
		LeituraTexto lida = LeituraTexto.validada(
				List.of("Inteligência Artificial", "inteligencia artificial ", "Mercado de Trabalho",
						"quarto tema", "outro"),
				Intencao.PERGUNTA, "  resumo do texto  ", true);

		assertThat(lida.temas()).containsExactly("inteligencia artificial", "mercado de trabalho", "quarto tema");
		assertThat(lida.intencao()).isEqualTo(Intencao.PERGUNTA);
		assertThat(lida.resumo()).isEqualTo("resumo do texto");
		assertThat(lida.lidoPeloModelo()).isTrue();
	}

	@Test
	void intencaoInvalidaDegradaSemQuebrar() {
		assertThat(Intencao.parse("banana")).isEqualTo(Intencao.DESCONHECIDA);
		assertThat(Intencao.parse(null)).isEqualTo(Intencao.DESCONHECIDA);
		assertThat(Intencao.parse("pergunta")).isEqualTo(Intencao.PERGUNTA);
	}

	@Test
	void cachePagaAInferenciaUmaVezPorTexto() {
		var leitor = new LeitorFalso();
		var cache = new CacheFalso();
		var servico = new LeituraDeTexto(leitor, cache);

		var primeira = servico.ler("um texto grande com assunto de verdade sobre mercado de trabalho");
		var segunda = servico.ler("um texto grande com assunto de verdade sobre mercado de trabalho");

		assertThat(primeira.inferido()).isTrue();
		assertThat(segunda.inferido()).isFalse();
		assertThat(leitor.chamadas).isEqualTo(1);
		assertThat(segunda.leitura().temas()).containsExactly("mercado de trabalho");
	}

	@Test
	void semModeloDegradaEZeraProcedencia() {
		var leitor = new LeitorFalso();
		leitor.ativo = false;
		var servico = new LeituraDeTexto(leitor, new CacheFalso());

		var resultado = servico.ler("um texto grande sem leitura possível aqui");

		assertThat(resultado.leitura().lidoPeloModelo()).isFalse();
		assertThat(resultado.leitura().temTemas()).isFalse();
		assertThat(resultado.leitura().intencao()).isEqualTo(Intencao.DESCONHECIDA);
		assertThat(leitor.chamadas).isZero();
	}

	@Test
	void leituraEmLoteNaoRepeteTextoJaLido() {
		var leitor = new LeitorFalso();
		var servico = new LeituraDeTexto(leitor, new CacheFalso());
		List<String> textos = List.of("primeiro texto sobre mercado de trabalho", "primeiro texto sobre mercado de trabalho",
				"segundo texto sobre inteligencia artificial", "curto");

		servico.lerEmLote(textos);
		servico.lerEmLote(textos);

		// Dois textos distintos com tamanho suficiente: lidos uma vez cada, nunca duas.
		assertThat(leitor.chamadas).isEqualTo(2);
	}

	@Test
	void hashDoConteudoEStavel() {
		assertThat(LeituraDeTexto.hash("abc")).isEqualTo(LeituraDeTexto.hash("abc"))
				.isNotEqualTo(LeituraDeTexto.hash("abd"));
	}

	static final class LeitorFalso implements LeitorDeTexto {
		boolean ativo = true;
		int chamadas;

		@Override
		public boolean disponivel() {
			return ativo;
		}

		@Override
		public Optional<LeituraTexto> ler(String texto) {
			// Mesmo limiar do adaptador real: texto curto demais não paga inferência.
			if (texto == null || texto.strip().length() < 24) {
				return Optional.empty();
			}
			chamadas++;
			if (texto.contains("mercado")) {
				return Optional.of(LeituraTexto.validada(List.of("mercado de trabalho"), Intencao.OPINIAO,
						"fala do mercado", true));
			}
			return Optional.of(LeituraTexto.validada(List.of("inteligencia artificial"), Intencao.EXPLICACAO,
					"explica ia", true));
		}
	}

	static final class CacheFalso implements LeituraCache {
		private final Map<String, LeituraTexto> linhas = new HashMap<>();

		@Override
		public Map<String, LeituraTexto> porHashes(java.util.Collection<String> hashes) {
			Map<String, LeituraTexto> achadas = new HashMap<>();
			for (String h : hashes) {
				if (linhas.containsKey(h)) {
					achadas.put(h, linhas.get(h));
				}
			}
			return achadas;
		}

		@Override
		public void salvar(String hash, LeituraTexto leitura, int versao) {
			linhas.put(hash, leitura);
		}

		@Override
		public long degradadas() {
			return linhas.values().stream().filter(l -> !l.lidoPeloModelo()).count();
		}
	}
}
