package com.socialcoisa.rede.idempotency.application;

public record CommandResponse(int status, Object body) {
}
