package com.socialcoisa.rede.shared.web;

import java.time.Instant;

public record ErrorResponse(String code, String message, Instant timestamp) {
}
