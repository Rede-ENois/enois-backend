package com.socialcoisa.rede.calls.application;

import java.util.List;

/**
 * O encontro, lido de fora dos eventos sem arrastar o serviço inteiro para as chamadas.
 *
 * <p>A sala do encontro é aberta a quem quiser entrar — é o combinado do "Tamo junto" nos eventos.
 * O que este port garante é que a sala pertence a um encontro que existe: sem isso, um id
 * inventado abriria uma sala sem dono.
 */
public interface EventLookup {

	/**
	 * O encontro existe?
	 *
	 * @throws com.socialcoisa.rede.shared.web.ApiException 404 quando não existe
	 */
	void requireExists(String eventId);

	/**
	 * O encontro existe E tem a sala "Tamo junto" ligada?
	 *
	 * <p>A sala é escolha de quem cria o evento, não algo que todo encontro ganha: abrir uma sala
	 * num evento que não a pediu seria criar um lugar de conversa que o organizador não quis.
	 *
	 * @throws com.socialcoisa.rede.shared.web.ApiException 404 quando não existe; 409 quando existe
	 * mas não tem a sala
	 */
	void requireTamoJunto(String eventId);

	/**
	 * Quem deve saber que o encontro está ao vivo: quem criou e quem confirmou presença. É só o
	 * aviso — a entrada na sala continua aberta a qualquer pessoa com sessão.
	 */
	List<String> interestedIn(String eventId);
}
