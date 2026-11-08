-- Repositório fantasma no registro de contribuições.
--
-- O worker de selos agora desliga a linha quando o GitHub responde 404 para ela
-- (repositório apagado ou privado): a varredura deixa de pagar o 404 por ela em toda
-- rodada. Esta migração já desliga o caso conhecido do relato (#462), sem esperar a
-- próxima sincronização.
--
-- Aditiva e idempotente: só toca na linha do repositório que não existe mais. O que já
-- foi importado dele fica no banco; se o repositório voltar, a administração o reabilita.
update github_repositories
set enabled = false
where repository = 'Rede-ENois/smartcontracts' and enabled = true;
