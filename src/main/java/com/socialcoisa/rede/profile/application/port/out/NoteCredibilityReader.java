package com.socialcoisa.rede.profile.application.port.out;

/**
 * Notas de rodapé aprovadas/negadas pela moderação. Vive no perfil porque o componente de
 * integridade da credibilidade usa as duas contagens — a implementação vem do feed, onde a nota
 * vive.
 *
 * <p>Nota aprovada é fact-check da comunidade: bônus de até 5 pontos cada (contando no máximo 5
 * notas, teto +25). Nota negada é informação falsa e desconta 25/75/150. Nota pendente ou
 * descartada (perdeu a vez para outra aprovada) não muda nada.</p>
 */
public interface NoteCredibilityReader {

	int countDeniedByUser(String userId);

	int countApprovedByUser(String userId);

	/** Notas negadas de cada conta do conjunto, em uma consulta (GROUP BY no autor). */
	java.util.Map<String, Integer> deniedCountsByUsers(java.util.Collection<String> userIds);

	/** Notas aprovadas de cada conta do conjunto, em uma consulta. */
	java.util.Map<String, Integer> approvedCountsByUsers(java.util.Collection<String> userIds);
}
