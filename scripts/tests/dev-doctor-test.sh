#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCTOR="$ROOT/scripts/dev-doctor.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Criamos comandos simulados em um PATH isolado.
# O script original usa head, sed e grep para processar resultados.
for tool in bash head sed grep dirname pwd; do
    location="$(command -v "$tool")"
    ln -s "$location" "$TMP/$tool"
done

make_command() {
    local name="$1"
    local contents="$2"
    printf '%s\n' '#!/bin/bash' "$contents" > "$TMP/$name"
    chmod +x "$TMP/$name"
}

make_command git 'exit 0'
make_command java 'echo '\''openjdk version "25.0.4"'\'' >&2'
make_command javac 'echo "javac 25.0.4"'
make_command docker 'exit 0'
make_command ss 'exit 0'

run_case() {
    local label="$1"
    local expected_exit="$2"
    local expected_text="$3"
    local actual_exit=0

    PATH="$TMP" /bin/bash "$DOCTOR" > "$TMP/output" 2>&1 ||
        actual_exit=$?

    if [[ "$actual_exit" == "$expected_exit" ]] &&
        /bin/grep -Fq "$expected_text" "$TMP/output"; then
        printf '[PASS] %s\n' "$label"
    else
        printf '[FAIL] %s (exit=%s, esperado=%s)\n' \
            "$label" "$actual_exit" "$expected_exit"
        /bin/cat "$TMP/output"
        return 1
    fi
}

run_case "Ambiente válido" 0 "Pré-requisitos essenciais encontrados."

make_command java 'echo '\''openjdk version "21.0.1"'\'' >&2'
run_case "Java 21 rejeitado" 1 "Java 25 necessário"

make_command java 'echo '\''openjdk version "25.0.4"'\'' >&2'
rm "$TMP/javac"
run_case "Compilador ausente" 1 "Compilador javac ausente"

make_command javac 'echo "javac 25.0.4"'
rm "$TMP/docker"
run_case "Docker opcional" 0 "Docker ausente"

printf '\nTodos os cenários passaram.\n'
