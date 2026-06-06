package com.socialcoisa.rede.identity.application;

public record SignupCommand(String name, String role, String email, String password) {
}
