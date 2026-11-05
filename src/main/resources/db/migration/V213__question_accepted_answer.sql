-- Duvidas (posts QUESTION) com resposta aceita: quem perguntou marca o comentario que resolveu.
-- accepted_comment_id aponta para comments.id (sem FK, regra do projeto); resolved_at e quando a
-- duvida foi marcada como resolvida. Linhas existentes ficam com as duas colunas nulas, ou seja,
-- toda duvida ja publicada continua "sem resposta aceita".
alter table posts add column if not exists accepted_comment_id varchar(255) null;
alter table posts add column if not exists resolved_at timestamp null;
