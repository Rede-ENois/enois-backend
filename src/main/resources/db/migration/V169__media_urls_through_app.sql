-- Midia passa a ser servida pelo proprio app.
--
-- O gateway S3 do b7cloud nao responde CORS e o bucket nao e publico: o navegador nao consegue
-- subir nem ler nada direto do `s3.b7cloud.tech`. O app agora expoe `GET/PUT /api/storage/{chave}`,
-- entao os enderecos gravados apontam para o nosso host e o pod fala com o bucket por dentro.
--
-- Troca so o PREFIXO: o caminho depois dele e o mesmo `objectKey` que ja esta no bucket, entao
-- nenhum objeto precisa ser remapeado. Idempotente: o WHERE so pega quem ainda tem o prefixo velho.

UPDATE media_assets SET cdn_url = REPLACE(cdn_url, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE cdn_url LIKE '%https://cdn.enois.ia.br/%';
UPDATE media_assets SET cdn_url = REPLACE(cdn_url, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE cdn_url LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE media_assets SET s3_cdn_url = REPLACE(s3_cdn_url, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE s3_cdn_url LIKE '%https://cdn.enois.ia.br/%';
UPDATE media_assets SET s3_cdn_url = REPLACE(s3_cdn_url, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE s3_cdn_url LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE users SET avatar = REPLACE(avatar, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://cdn.enois.ia.br/%';
UPDATE users SET avatar = REPLACE(avatar, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE users SET banner = REPLACE(banner, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://cdn.enois.ia.br/%';
UPDATE users SET banner = REPLACE(banner, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE users SET badge = REPLACE(badge, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE badge LIKE '%https://cdn.enois.ia.br/%';
UPDATE users SET badge = REPLACE(badge, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE badge LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE users SET badges = REPLACE(badges, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE badges LIKE '%https://cdn.enois.ia.br/%';
UPDATE users SET badges = REPLACE(badges, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE badges LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE users SET banner_html = REPLACE(banner_html, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://cdn.enois.ia.br/%';
UPDATE users SET banner_html = REPLACE(banner_html, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE users SET resume_url = REPLACE(resume_url, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE resume_url LIKE '%https://cdn.enois.ia.br/%';
UPDATE users SET resume_url = REPLACE(resume_url, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE resume_url LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE communities SET avatar = REPLACE(avatar, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://cdn.enois.ia.br/%';
UPDATE communities SET avatar = REPLACE(avatar, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE communities SET cover = REPLACE(cover, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://cdn.enois.ia.br/%';
UPDATE communities SET cover = REPLACE(cover, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE communities SET banner_html = REPLACE(banner_html, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://cdn.enois.ia.br/%';
UPDATE communities SET banner_html = REPLACE(banner_html, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE companies SET logo = REPLACE(logo, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE logo LIKE '%https://cdn.enois.ia.br/%';
UPDATE companies SET logo = REPLACE(logo, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE logo LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE companies SET cover = REPLACE(cover, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://cdn.enois.ia.br/%';
UPDATE companies SET cover = REPLACE(cover, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE corporate_profiles SET avatar = REPLACE(avatar, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://cdn.enois.ia.br/%';
UPDATE corporate_profiles SET avatar = REPLACE(avatar, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE corporate_profiles SET banner = REPLACE(banner, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://cdn.enois.ia.br/%';
UPDATE corporate_profiles SET banner = REPLACE(banner, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE corporate_profiles SET banner_html = REPLACE(banner_html, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://cdn.enois.ia.br/%';
UPDATE corporate_profiles SET banner_html = REPLACE(banner_html, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE developer_profiles SET avatar = REPLACE(avatar, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://cdn.enois.ia.br/%';
UPDATE developer_profiles SET avatar = REPLACE(avatar, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE developer_profiles SET cover = REPLACE(cover, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://cdn.enois.ia.br/%';
UPDATE developer_profiles SET cover = REPLACE(cover, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE events SET cover = REPLACE(cover, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://cdn.enois.ia.br/%';
UPDATE events SET cover = REPLACE(cover, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE games SET icon = REPLACE(icon, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE icon LIKE '%https://cdn.enois.ia.br/%';
UPDATE games SET icon = REPLACE(icon, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE icon LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE games SET banner = REPLACE(banner, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://cdn.enois.ia.br/%';
UPDATE games SET banner = REPLACE(banner, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE games SET cover = REPLACE(cover, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://cdn.enois.ia.br/%';
UPDATE games SET cover = REPLACE(cover, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE games SET banner_html = REPLACE(banner_html, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://cdn.enois.ia.br/%';
UPDATE games SET banner_html = REPLACE(banner_html, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE groups SET avatar = REPLACE(avatar, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://cdn.enois.ia.br/%';
UPDATE groups SET avatar = REPLACE(avatar, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE avatar LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE groups SET banner = REPLACE(banner, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://cdn.enois.ia.br/%';
UPDATE groups SET banner = REPLACE(banner, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE groups SET banner_html = REPLACE(banner_html, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://cdn.enois.ia.br/%';
UPDATE groups SET banner_html = REPLACE(banner_html, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner_html LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE jobs SET logo = REPLACE(logo, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE logo LIKE '%https://cdn.enois.ia.br/%';
UPDATE jobs SET logo = REPLACE(logo, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE logo LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE mentors SET banner = REPLACE(banner, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://cdn.enois.ia.br/%';
UPDATE mentors SET banner = REPLACE(banner, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE banner LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE support_requests SET image_url = REPLACE(image_url, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE image_url LIKE '%https://cdn.enois.ia.br/%';
UPDATE support_requests SET image_url = REPLACE(image_url, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE image_url LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
UPDATE community_projects SET cover = REPLACE(cover, 'https://cdn.enois.ia.br/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://cdn.enois.ia.br/%';
UPDATE community_projects SET cover = REPLACE(cover, 'https://s3.b7cloud.tech/enois-bucket/', 'https://api.enois.ia.br/api/storage/')
 WHERE cover LIKE '%https://s3.b7cloud.tech/enois-bucket/%';
