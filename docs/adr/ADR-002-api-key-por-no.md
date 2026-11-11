# ADR-002 — API key por nó, gerada por admin, com hash no banco

**Status:** aceita (Fase 0) — requisito direto da mantenedora
**Data:** 2026-11-11

## Contexto

Um admin precisa poder cadastrar uma API key que conecta um nó ao Control Plane: **uma chave por
nó, gerada na tela de administração**.

O projeto já tem dois padrões que servem de base:

- `shared/security/Hashes.sha256Hex` — segredo de uso único persistido só como hash;
- credencial de serviço fail-closed (`X-Deploy-Token`) conferida no controller, com 503 quando não
  configurada.

E já tem autorização de admin por coluna imutável (`AdminGuard`) com trava central
(`AdminRouteInterceptor`) para todo `/admin/**`.

## Decisão

1. **Uma chave por nó**, emitida por um admin em `POST /admin/network/nodes/{id}/keys`.
2. A chave em claro é **exibida uma única vez**, na resposta do `POST`. Depois existe apenas o hash
   (SHA-256 hex, unicidade no banco).
3. **Revogação** e **rotação** são ações administrativas, auditadas em `audit_logs`.
4. A chave **não substitui** o certificado do nó: o certificado prova o canal (mTLS), a chave prova
   a autorização.
5. A chave **nunca** entra em log, auditoria, evento de outbox ou resposta de listagem.
6. Formato `enk_<nodeIdCurto>_<32 bytes base64url>` — o prefixo permite reconhecer a chave em
   varredura de segredo sem revelar nada.

## Consequências

**Positivas**
- Revogar um participante é uma operação de tela, com efeito imediato e registro.
- Comprometer a chave de um nó não dá acesso a outro nó.
- O padrão é o mesmo do resto do projeto: sem inventar criptografia própria.

**Negativas**
- O admin é ponto de criação: se não houver admin disponível, não entra nó novo.
- A exibição única exige que a tela não recarregue antes do "copiar" — o contrato precisa deixar
  claro que a chave não é recuperável.

## Alternativas descartadas

- **Chave compartilhada entre nós da rede:** viola o requisito e destrói a capacidade de revogar um
  participante sem derrubar todos.
- **Chave só como variável de ambiente:** não é cadastrada na tela, não é auditável e não tem
  revogação.
- **Chave em claro no banco:** qualquer leitura do banco compromete a rede inteira.
- **JWT de longa duração como chave:** revogação de JWT exige lista de revogação, que é exatamente a
  tabela que já vamos ter — a chave com hash é mais simples e igualmente segura.

## Relacionado

- [04 — Identidade e autorização](../rede-distribuida/04-identidade-e-autorizacao.md)
- [ADR-003 — token de provisionamento separado da chave](ADR-003-provisionamento.md)
