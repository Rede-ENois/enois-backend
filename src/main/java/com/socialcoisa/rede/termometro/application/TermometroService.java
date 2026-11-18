package com.socialcoisa.rede.termometro.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.termometro.application.port.out.TermometroRepository;
import com.socialcoisa.rede.termometro.domain.CorteTermico;
import com.socialcoisa.rede.termometro.domain.Emocao;
import com.socialcoisa.rede.termometro.domain.TermometroPeriodo;

/**
 * A leitura do Termômetro para o painel: as janelas em ordem, com porcentagens calculadas das
 * contagens brutas e o recorte de amostra — janela com menos gente que o chão não vira
 * indicador, vira aviso de amostra pequena.
 *
 * A view não carrega nada de individual: não existe por autor, não existe por comunidade e
 * não existe texto — o que o worker agregou é o teto do que a tela vê.
 */
@Component
public class TermometroService {

	/** A emoção pronta para a tela: nome estável, porcentagem e contagem bruta. */
	public record EmocaoPainel(String emocao, int porcentagem, long contagem) {
	}

	/** A janela pronta para a tela: período, emoções ordenadas da maior para a menor porcentagem. */
	public record JanelaPainel(String periodo, boolean amostraSuficiente, long manifestacoes,
			long participantes, String dominante, List<EmocaoPainel> emocoes) {
	}

	private final TermometroRepository repository;

	TermometroService(TermometroRepository repository) {
		this.repository = repository;
	}

	/** As quatro janelas do painel, na ordem de exibição; janela sem corte é janela vazia. */
	public List<JanelaPainel> painel() {
		List<JanelaPainel> painel = new ArrayList<>();
		for (TermometroPeriodo periodo : TermometroPeriodo.emOrdemDePainel()) {
			painel.add(janelaDe(periodo));
		}
		return painel;
	}

	private JanelaPainel janelaDe(TermometroPeriodo periodo) {
		CorteTermico corte = repository.corteDe(periodo).orElse(CorteTermico.vazio(periodo));
		List<EmocaoPainel> emocoes = new ArrayList<>();
		for (Emocao emocao : Emocao.values()) {
			emocoes.add(new EmocaoPainel(emocao.name(), corte.porcentagemDe(emocao),
					corte.contagens().getOrDefault(emocao, 0L)));
		}
		emocoes.sort((a, b) -> Integer.compare(b.porcentagem(), a.porcentagem()));
		Emocao dominante = corte.dominante();
		return new JanelaPainel(periodo.name(), corte.temAmoSuficiente(), corte.manifestacoes(),
				corte.participantes(), dominante == null ? null : dominante.name(), emocoes);
	}

}
