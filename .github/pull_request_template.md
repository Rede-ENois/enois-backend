## O que muda

<!-- 2 a 5 linhas: o problema e o que a mudança faz -->

Closes #<!-- número da issue de origem: toda PR tem uma -->

## Como testar

<!-- passos, curl, ou "abrir X e clicar em Y" -->

## Evidência

<!-- Prova, não promessa: comando rodado, quantos testes passaram, o que foi conferido à mão. -->

- `./mvnw -DskipTests compile` → <!-- verde/vermelho -->
- `./mvnw -Dtest=... test` → <!-- N/N passaram -->
- suíte completa (se o escopo pediu) → <!-- N/N -->
- conferido à mão: <!-- o quê, onde -->

## Risco e rollback

<!-- dinheiro, auth, migração, dados? como desfazer? -->

## Checklist

- [ ] PR aponta para `qa` (ou `main` só se vier de `qa`/`hotfix`)
- [ ] Uma feature por PR — sem refactor ou correção alheia pegando carona
- [ ] Issue de origem aberta com o plano de implementação, referenciada acima
- [ ] CI verde (lint, testes, build)
- [ ] Teste de prova incluído: falha no código antigo, passa no novo
- [ ] Sem stub, placeholder, `TODO` fingindo conclusão, fallback silencioso
- [ ] Sem asserção enfraquecida, sem `@Disabled` escondido, sem skip para ficar verde
- [ ] Sem segredo, token, dump ou dado pessoal
- [ ] Sem detalhe de correção de segurança no texto público (commit e PR)
- [ ] Migração Flyway nova (se houver) e testada
- [ ] Print/vídeo se mudou tela (mobile e desktop)
- [ ] Design system respeitado: componente existente, token de tema, contraste AA, acessibilidade
- [ ] Contrato de API/documentação atualizado (se mudou)
- [ ] Autorrevisão do `AGENTS.md` feita (`git diff` lido linha a linha, cada linha entendida)
- [ ] AI tools helped write this change (check if yes; no tool name or attribution line needed)
