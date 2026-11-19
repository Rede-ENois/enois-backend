package com.socialcoisa.rede.paporeto.application.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * A autoria que a tela do Papo Reto pode mostrar: nome de exibição e @ de quem assinou uma
 * publicação relacionada, e quem está banido (nome que não aparece).
 *
 * Fica como porta própria porque o Papo Reto não precisa do perfil inteiro — só do que a
 * listagem desenha — e porque a decisão de sanção é do módulo dono dela. O autor_id em si
 * nunca sai daqui para a resposta.
 */
public interface PapoAutorReader {

	/** Nome e @ por id; conta que não existe simplesmente não volta no mapa. */
	Map<String, Autor> autores(Collection<String> ids);

	/** Quais destes ids estão banidos agora. */
	Set<String> banidos(Collection<String> ids);

	/** O que a listagem mostra de uma conta. */
	record Autor(String nome, String handle) {
	}

	/** Implementação neutra para teste e para quem não tem a leitura de perfis. */
	static PapoAutorReader none() {
		return new PapoAutorReader() {
			@Override
			public Map<String, Autor> autores(Collection<String> ids) {
				return Map.of();
			}

			@Override
			public Set<String> banidos(Collection<String> ids) {
				return Set.of();
			}
		};
	}

}
