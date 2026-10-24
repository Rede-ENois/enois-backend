#!/usr/bin/env bash

set -u

# Localiza o projeto independentemente do diretório de execução.
PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

PASS=0
WARN=0
FAIL=0

pass() {
    printf '[PASS] %s\n' "$1"
    PASS=$((PASS + 1))
}

warn() {
    printf '[WARN] %s\n' "$1"
    WARN=$((WARN + 1))
}

fail() {
    printf '[FAIL] %s\n' "$1"
    FAIL=$((FAIL + 1))
}

printf '\n=== Énóis Dev Doctor ===\n\n'

# 1. Git
if command -v git >/dev/null 2>&1; then
    pass "Git encontrado"
else
    fail "Git ausente. Instale o Git para contribuir."
fi

# 2. Java 25
if command -v java >/dev/null 2>&1; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1)
    JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION" |
        sed -nE 's/.*version "([0-9]+).*/\1/p')

    if [[ "$JAVA_MAJOR" == "25" ]]; then
        pass "Java 25 encontrado"
    else
        fail "Java 25 necessário. Versão encontrada: $JAVA_VERSION"
    fi
else
    fail "Java ausente. Instale JDK 25."
fi

# 3. Compilador Java
if command -v javac >/dev/null 2>&1; then
    JAVAC_VERSION=$(javac -version 2>&1)
    if [[ "$JAVAC_VERSION" =~ ^javac[[:space:]]25([.+[:space:]]|$) ]]; then
        pass "Compilador JDK 25 encontrado"
    else
        fail "Compilador JDK 25 necessário. Encontrado: $JAVAC_VERSION"
    fi
else
    fail "Compilador javac ausente. Instale o JDK 25 completo."
fi

# 4. Maven Wrapper
if [[ -f "$PROJECT_ROOT/mvnw" && -f "$PROJECT_ROOT/.mvn/wrapper/maven-wrapper.properties" ]]; then
    pass "Maven Wrapper encontrado"
else
    fail "Maven Wrapper não encontrado. Execute na raiz do repositório."
fi

# 5. Docker (opcional)
if command -v docker >/dev/null 2>&1; then
    pass "Docker encontrado (opcional)"
else
    warn "Docker ausente. Não é necessário para usar H2."
fi

# 6. Porta da aplicação
if command -v ss >/dev/null 2>&1; then
    if ss -ltn '( sport = :8081 )' 2>/dev/null |
        grep -q ':8081'; then
        warn "Porta 8081 em uso. Verifique antes de iniciar a aplicação."
    else
        pass "Porta 8081 disponível"
    fi
else
    warn "Não foi possível verificar a porta 8081 (comando ss ausente)."
fi

printf '\n=== Resultado ===\n'
printf 'PASS: %d | WARN: %d | FAIL: %d\n' "$PASS" "$WARN" "$FAIL"

if (( FAIL > 0 )); then
    printf 'Ambiente precisa de ajustes antes do desenvolvimento.\n'
    exit 1
fi

printf 'Pré-requisitos essenciais encontrados.\n'
exit 0
