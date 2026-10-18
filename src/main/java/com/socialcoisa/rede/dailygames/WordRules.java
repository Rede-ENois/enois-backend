package com.socialcoisa.rede.dailygames;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/** Pure rules for five-letter guesses. The answer never leaves the service. */
public final class WordRules {
    private WordRules() {}

    public static String normalize(String input) {
        if (input == null) return "";
        return Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    public static String[] evaluate(String answer, String guess) {
        if (!answer.matches("[A-Z]{5}") || !guess.matches("[A-Z]{5}"))
            throw new IllegalArgumentException("Palavra deve ter cinco letras.");
        String[] result = new String[5];
        int[] remaining = new int[26];
        for (int i = 0; i < 5; i++) {
            if (answer.charAt(i) == guess.charAt(i)) result[i] = "CORRECT";
            else remaining[answer.charAt(i) - 'A']++;
        }
        for (int i = 0; i < 5; i++) if (result[i] == null) {
            int letter = guess.charAt(i) - 'A';
            if (remaining[letter] > 0) { result[i] = "PRESENT"; remaining[letter]--; }
            else result[i] = "ABSENT";
        }
        return result;
    }
}
