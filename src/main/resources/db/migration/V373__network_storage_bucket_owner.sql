-- O dono do bucket, gravado no CreateBucket e nunca reescrito.
--
-- Antes, a posse vinha de `updated_by`, que muda a cada `putBucketPolicy` — quem
-- configurava o ciclo de vida virava "dono" — e, sem linha de política, do primeiro
-- objeto vivo, que muda quando todos são apagados e outra conta grava. O criador
-- agora tem coluna própria.
--
-- Nula nos buckets que já existiam: `ownerDoBucket` cai no fallback da política e no
-- primeiro objeto vivo, a migração preguiçosa de sempre.
alter table network_storage_buckets add column if not exists owner_user_id varchar(40) null;