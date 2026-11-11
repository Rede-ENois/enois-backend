# ADR-003 — Token de provisionamento separado da API key

**Status:** aceita (Fase 0)
**Data:** 2026-11-11

## Contexto

O onboarding tem dois momentos distintos: **criar** o nó e **operar** o nó. Usar a mesma credencial
para os dois cria um problema: a credencial que o participante recebe antes de existir precisa ser
transportada por canal humano (chat, e-mail), enquanto a credencial de operação fica na máquina.

## Decisão

Duas credenciais, com ciclos de vida independentes:

| credencial | quando | validade | uso |
|---|---|---|---|
| token de provisionamento | antes do enroll | TTL curto (default 30 min), **uso único** | cria o nó |
| API key do nó | no enroll | até revogação | opera o nó |

O token é consumido no `POST /network/nodes/enroll`; a segunda tentativa recebe `NODE_TOKEN_USED`
(409). O token expirado recebe `NODE_TOKEN_EXPIRED` (400). Ambos são persistidos só como hash.

## Consequências

**Positivas**
- Vazamento do token tem janela curta e uso único: não vira acesso permanente.
- A API key nunca trafega por canal humano — nasce na resposta do enroll, direto para o runtime.
- Revogar a operação não exige reemitir token, e vice-versa.

**Negativas**
- Dois segredos para gerenciar em vez de um.
- O fluxo de onboarding tem um passo a mais na tela de admin.

## Alternativas descartadas

- **Um único segredo para tudo:** vazamento do token de onboarding vira acesso permanente.
- **Token sem TTL:** um token esquecido em um chat continua válido para sempre.
- **Token reutilizável:** permite que um segundo servidor se registre com a credencial do primeiro.
