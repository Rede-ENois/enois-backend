package com.socialcoisa.rede.network.domain;

/**
 * O campo que a borda serve no lugar da projeção de perfil — e o que ele deliberadamente não tem.
 *
 * <p>A projeção de perfil já é pública e é a mesma para todo leitor (sem e-mail, sem dado de
 * sessão), por isso pode sair da infraestrutura. Mas "público" não quer dizer "todo mundo deveria
 * receber": a cidade/UF só aparecem quando a pessoa ligou {@code showCity}, e a foto, a capa e a
 * bio em HTML sanitizada já trafegam para o browser do visitante.</p>
 *
 * <p>Esta projeção carrega exatamente o que a borda precisa para desenhar o cartão público, e nada
 * além disso. Ela é o contrato: um campo novo só entra aqui quando for comprovadamente público, e
 * o teste que a compara com {@code UserProfileView} falha quando o view ganha campo que ficou de
 * fora — é de propósito, para a decisão ser tomada, não esquecida.</p>
 *
 * <p>Nunca entra aqui: e-mail (já não está no view), {@code resumeUrl}/{@code resumeName} (só o
 * dono lê), {@code joinedAt} (a borda não ordena por chegada), garden e aparência (pesados e não
 * fazem parte do cartão), e o selo de humanidade (é decisão do Control Plane sobre dado de
 * verificação — a borda não replica).</p>
 */
public record UserProfileProjection(String id, String username, String name, String headline, String company,
		String location, String avatar, String banner, String badge, String bio, String bioHtml, String bannerHtml,
		java.util.List<String> skills, java.util.List<String> goals, String availability, String portfolio,
		java.util.List<String> externalLinks, boolean openToWork, boolean isMentor, int connectionsCount,
		int followersCount, int reputation, Integer postsCount, String pronouns, java.util.List<String> badges,
		String aka, String avatarFrame, String nameTheme, String nameFont, String nameColor,
		String contributorTagColor, String cardTheme, String nameplate, String bannerItem, String profileEffect,
		String statusText, String statusIcon, String city, String state, String gardenStyle) {
}
