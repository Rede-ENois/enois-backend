package com.socialcoisa.rede.mentoring.application.port.out;

import java.util.List;
import java.util.Optional;

/** A parte da API pública do Jitsi que a sala de mentoria usa: dial-in e PIN da conferência. */
public interface JitsiDialInGateway {

	/**
	 * Números de telefone para entrar na conferência. Vazio quando a API não responde — a sala no
	 * navegador continua funcionando, o discagem é extra.
	 *
	 * @param conferenceName conferência completa, no formato sala@conference.host
	 */
	List<String> phoneNumbers(String conferenceName);

	/** PIN numérico da conferência, ou vazio quando a API não o tem. */
	Optional<String> conferencePin(String conferenceName);

}
