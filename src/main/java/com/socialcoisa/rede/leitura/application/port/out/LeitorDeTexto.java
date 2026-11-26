package com.socialcoisa.rede.leitura.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.leitura.domain.LeituraTexto;

/**
 * Lê um texto e diz o que ele é: temas, intenção e resumo.
 *
 * <p>Devolve {@link Optional#empty()} quando não há leitura possível (modelo indisponível, texto
 * curto demais, erro de rede). NUNCA joga exceção para o chamador: a ausência de leitura é um
 * estado normal do sistema, não uma falha — quem chama degrada para a extração determinística.</p>
 */
public interface LeitorDeTexto {

	/** A leitura está disponível agora? (chave configurada). */
	boolean disponivel();

	/** Lê o texto. Vazio quando não há como ler ou quando o texto é insuficiente. */
	Optional<LeituraTexto> ler(String texto);
}
