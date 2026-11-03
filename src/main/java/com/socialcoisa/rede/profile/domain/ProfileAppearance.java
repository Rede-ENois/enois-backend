package com.socialcoisa.rede.profile.domain;

/**
 * A aparência dos DOIS perfis da conta, guardada junta e entregue separada.
 *
 * Social e profissional são identidades distintas: quem escolhe um visual para o perfil
 * pessoal não quer, necessariamente, o mesmo no perfil de trabalho. Por isso os dois lados
 * são independentes — mudar um nunca mexe no outro.
 */
public record ProfileAppearance(ProfileLook social, ProfileLook professional) {

	private static final ProfileAppearance EMPTY = new ProfileAppearance(ProfileLook.empty(), ProfileLook.empty());

	public static ProfileAppearance empty() {
		return EMPTY;
	}

	public ProfileLook social() {
		return social == null ? ProfileLook.empty() : social;
	}

	public ProfileLook professional() {
		return professional == null ? ProfileLook.empty() : professional;
	}

	public boolean isEmpty() {
		return social().isEmpty() && professional().isEmpty();
	}
}
