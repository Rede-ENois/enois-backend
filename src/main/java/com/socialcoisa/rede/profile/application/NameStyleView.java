package com.socialcoisa.rede.profile.application;

import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Personalizacao visual do nome (tema, fonte, cor e plaquinha equipada) escolhida no perfil. Viaja junto com o nome em
 * toda projecao que o exibe (post, comentario, notificacao, ranking, equipe...), sempre como nulo
 * quando a pessoa nao personalizou — o front aplica o padrao do design system nesse caso.
 */
public record NameStyleView(String theme, String font, String color, String nameplate) {

	public static NameStyleView from(UserProfile profile) {
		if (profile == null) {
			return null;
		}
		return of(profile.nameTheme(), profile.nameFont(), profile.nameColor(), profile.nameplate());
	}

	/** Mesma regra, a partir da projeção em lote (as vitrines não carregam o perfil inteiro). */
	public static NameStyleView from(com.socialcoisa.rede.profile.application.port.out.SellerIdentity seller) {
		if (seller == null) {
			return null;
		}
		return of(seller.nameTheme(), seller.nameFont(), seller.nameColor(), seller.nameplate());
	}

	private static NameStyleView of(String theme, String font, String color, String nameplate) {
		if (theme == null && font == null && color == null && nameplate == null) {
			return null;
		}
		return new NameStyleView(theme, font, color, nameplate);
	}
}