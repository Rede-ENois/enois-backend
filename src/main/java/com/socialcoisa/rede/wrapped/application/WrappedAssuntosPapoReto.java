package com.socialcoisa.rede.wrapped.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.leitura.application.LeituraDeTexto;
import com.socialcoisa.rede.paporeto.application.PapoAssuntoExtractor;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedAssuntos;
import com.socialcoisa.rede.wrapped.domain.WrappedAssunto;

/**
 * O reconhecimento de assuntos da retrospectiva reaproveitando o Papo Reto.
 *
 * <p>Não duplica o mecanismo existente: usa o mesmo {@link PapoAssuntoExtractor} (limpeza,
 * dobra NFD, stopwords, unigramas/bigramas com conector, clustering guloso sem sobreposição) e só
 * agrega o que ele entrega. Determinístico: mesma entrada, mesma lista. Cada texto contribui uma
 * vez por expressão — a mesma regra de dedup por publicação do Papo Reto.</p>
 */
@Component
class WrappedAssuntosPapoReto implements WrappedAssuntos {

	private final PapoAssuntoExtractor extrator = new PapoAssuntoExtractor();

	/**
	 * A leitura de intenção, opcional: quando o modelo leu o texto, os TEMAS reais entram no lugar
	 * das expressões extraídas. Sem ela, degrada para a extração como antes.
	 */
	private final LeituraDeTexto leitura;

	WrappedAssuntosPapoReto(org.springframework.beans.factory.ObjectProvider<LeituraDeTexto> leitura) {
		this.leitura = leitura.getIfAvailable();
	}

	@Override
	public List<WrappedAssunto> extrair(List<String> textos, int limite) {
		Map<String, Integer> contagem = new LinkedHashMap<>();
		for (String texto : textos) {
			if (texto == null || texto.isBlank()) {
				continue;
			}
			if (leitura != null && leitura.disponivel()) {
				var lido = leitura.ler(texto).leitura();
				if (lido.lidoPeloModelo() && lido.temTemas()) {
					var vistos = new java.util.HashSet<String>();
					for (String tema : lido.temas()) {
						if (vistos.add(tema)) {
							contagem.merge(tema, 1, Integer::sum);
						}
					}
					continue;
				}
			}
			for (PapoAssuntoExtractor.Fala fala : extrator.extrair(texto, "POST")) {
				contagem.merge(fala.expressao(), 1, Integer::sum);
			}
		}
		return contagem.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
				.limit(limite)
				.map(e -> new WrappedAssunto(e.getKey(), e.getValue()))
				.toList();
	}
}
