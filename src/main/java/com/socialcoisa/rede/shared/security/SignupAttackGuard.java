package com.socialcoisa.rede.shared.security;

/**
 * Portão de cadastro contra o padrão de ataque conhecido (as contas "probe" de 20/10 e 22/10).
 *
 * <p>Fica em {@code shared} pelo mesmo motivo do {@link IpBlockReader}: a identidade precisa
 * barrar o cadastro e a moderação é dona da regra. A implementação recusa o cadastro e, depois de
 * repetições, pausa novos cadastros por um tempo (nunca o login, nunca um bloqueio permanente).
 */
public interface SignupAttackGuard {

	/**
	 * Lança 403 SIGNUP_REFUSED quando o cadastro bate no padrão de ataque ou quando a origem está
	 * em pausa. Cadastro normal não é tocado.
	 */
	void checkSignup(String name, String email, String ip);

	/**
	 * Mesma verificação, com a impressão do aparelho que o cliente enviou: é ela que distingue a
	 * conta isolada de um mesmo navegador repetindo o padrão atrás de redes diferentes.
	 */
	void checkSignup(String name, String email, String ip, String deviceFingerprint);
}
