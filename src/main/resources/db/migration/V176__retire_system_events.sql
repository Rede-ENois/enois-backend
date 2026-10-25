-- A monitorabilidade saiu do banco para arquivo JSONL na VPS (a observabilidade nao
-- disputa mais o pool com as requisicoes dos usuarios). A tabela fica aposentada: novas
-- linhas param de chegar aqui; a leitura do painel usa os arquivos. Mantida por uma versao
-- para consulta forense, depois sai em migracao propria.
-- Nao apaga de proposito: DROP sem ler antes derrubaria rollback e investigacao em andamento.
select 1;
