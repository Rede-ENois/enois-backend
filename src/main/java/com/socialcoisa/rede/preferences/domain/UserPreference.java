package com.socialcoisa.rede.preferences.domain;

import java.time.Instant;
import java.util.List;

/**
 * Preferências que acompanham a conta (uma linha por usuário).
 *
 * Os campos de lista chegam como CSV validado (o serviço valida cada item contra a
 * taxonomia/módulos reais antes de gravar). Valores nulos = "nunca configurado" — a
 * experiência continua igual à de antes da extensão.
 */
public record UserPreference(String userId, String themeId, String likedTopics, String blockedTopics,
		String disabledModules, boolean nightRestReminder, java.time.LocalDate nightRestDismissedOn,
		boolean liteMode, boolean batataMode, Instant updatedAt) {
}
