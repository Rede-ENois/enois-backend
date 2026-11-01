## O que muda

<!-- 2 a 5 linhas: o problema e o que a mudança faz -->

## Como testar

<!-- passos, curl, ou "abrir X e clicar em Y" -->

## Risco e rollback

<!-- dinheiro, auth, migração, dados? como desfazer? -->

## Checklist

- [ ] PR aponta para `qa` (ou `main` só se vier de `qa`/`hotfix`)
- [ ] CI verde (lint, testes, build)
- [ ] Sem segredo, token, dump ou dado pessoal
- [ ] Migração Flyway nova (se houver) e testada
- [ ] Print/vídeo se mudou tela (mobile e desktop)
- [ ] Contrato de API/documentação atualizado (se mudou)
- [ ] AI tools helped write this change (check if yes; no tool name or attribution line needed)
