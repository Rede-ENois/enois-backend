package com.socialcoisa.rede.shared.age;

/**
 * Regra de idade da plataforma (ECA Digital, Lei 15.211/2025), em um lugar só para que cadastro,
 * front e documentação não contem histórias diferentes.
 *
 * <p><b>16 anos</b> é a idade mínima para criar conta. De 16 a 17 anos a conta é de
 * <b>menor supervisionado</b>: pode navegar, mas a escrita (publicar, comentar, enviar mensagem)
 * só é liberada depois que um responsável adulto autoriza o uso — o que já é aplicado por
 * {@link AgeGate#requireCanWrite}. Completar 18 anos encerra a exigência automaticamente.
 *
 * <p>A data de nascimento é <b>declarada</b>, não verificada oficialmente: as proteções se
 * aplicam mesmo assim.
 */
public final class AgePolicy {

	/** Idade mínima para criar conta na plataforma. */
	public static final int MIN_AGE = 16;

	/**
	 * Idade a partir da qual a pessoa deixa de ser considerada menor: sem supervisão parental e
	 * com escrita liberada.
	 */
	public static final int ADULT_AGE = 18;

	private AgePolicy() {
	}
}
