package com.socialcoisa.rede.moderation.application.port.out;

import com.socialcoisa.rede.moderation.domain.Report;

/** Associação persistida da mídia, sem ler binários, URLs ou metadados privados. */
public interface MetadadosMidiaDenunciadaReader {
	boolean hasMediaOf(Report report);
}
