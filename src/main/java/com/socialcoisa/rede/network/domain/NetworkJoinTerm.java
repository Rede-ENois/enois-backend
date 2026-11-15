package com.socialcoisa.rede.network.domain;

/**
 * O contrato de responsabilidade que quem se inscreve para servir um nó aceita.
 *
 * <p>O texto e a versão vivem no código, não no banco: a versão é o que a linha da inscrição guarda
 * ({@code term_version}), e é ela que permite dizer, anos depois, <b>qual</b> contrato a pessoa
 * aceitou. Mudar o texto sem mudar a versão apagaria essa distinção — por isso os dois andam
 * juntos.</p>
 */
public final class NetworkJoinTerm {

	/** Versão vigente do contrato. Sobe sempre que o texto mudar de forma material. */
	public static final String VERSION = "1";

	public static final String TEXT = """
		CONTRATO DE RESPONSABILIDADE POR NÓ — REDE ÉNOIS (versão 1)

		Ao solicitar ingresso para servir um nó na Rede Énois, você declara e concorda que:

		1. Identificação. Os dados informados (nome, RG, CPF e e-mail) são verdadeiros e seus. A
		   administração pode recusar o pedido, ou revogar o nó depois de aprovado, quando houver
		   divergência ou suspeita de fraude.

		2. Responsabilidade pelo nó. Você responde pela máquina que serve: segurança, atualizações,
		   energia, conexão e pelo que ela armazena, processa e transporta na rede.

		3. Conteúdo e lei. O nó não pode ser usado para conteúdo ilícito, abuso, spam, fraude ou
		   violação de direitos de terceiros. Diante de indício, a administração pode suspender o nó
		   e comunicar as autoridades competentes.

		4. Conteúdo de terceiros. O nó transporta e guarda conteúdo de outras pessoas. Você não deve
		   inspecionar, copiar nem usar esse conteúdo para finalidade própria.

		5. Disponibilidade. Você pode desconectar o nó a qualquer momento, sem multa. A rede não
		   garante tráfego, recompensa nem continuidade do nó.

		6. Seus dados. RG e CPF são usados apenas para a decisão de ingresso e para
		   responsabilização. Não são exibidos publicamente nem enviados a outros nós.

		7. Revogação. A administração pode revogar o nó e as chaves a qualquer momento, com registro
		   em auditoria.

		8. Chave de provisionamento. A chave é exibida uma única vez, no link enviado por e-mail
		   depois da aprovação. Se ela for perdida depois de exibida, não há como recuperá-la: será
		   preciso fazer um novo pedido.
		""";

	private NetworkJoinTerm() {
	}
}