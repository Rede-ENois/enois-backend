## O que muda

<!-- Uma frase sobre o comportamento que muda. -->

## Por quê

<!-- O problema que isso resolve e o que acontecia antes. -->

## Migrações

<!-- Se houver: número da versão, o que cria/altera, e o que acontece com as linhas que já existem.
     Se não houver, escreva "sem migração" — é uma informação, não um vazio. -->

## Risco e rollback

<!-- Mexe em dinheiro, auth, migração ou dados? Como desfazer se der errado? -->

## Como foi verificado

<!-- Não vale "compila". Diga o que você fez:
     - testes: qual classe, quantos passaram (`./mvnw -Dtest=X test`)
     - teste manual/HTTP, se houver
     - e, quando mexer em migração, se rodou contra MariaDB de verdade -->

## Checklist

- [ ] PR aponta para `qa` (ou `main` só se vier de `qa`/`hotfix`)
- [ ] `./mvnw -DskipTests compile` passa
- [ ] Testes relevantes rodando (`./mvnw -Dtest=... test`)
- [ ] Sem erro novo de tipo/compilação introduzido
- [ ] Migração versionada (quando houver) e compatível com o processo de deploy
- [ ] Nada de segredo, token, dump ou dado pessoal no diff
- [ ] Contrato de API existente preservado (ou a mudança está documentada)
