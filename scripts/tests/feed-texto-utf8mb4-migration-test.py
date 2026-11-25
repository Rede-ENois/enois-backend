#!/usr/bin/env python3
"""Verifica o upgrade das colunas de texto livre do feed em MariaDB legado, sem porta publicada nem rede."""

import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[2]
IMAGE = os.environ.get(
    "MARIADB_TEST_IMAGE",
    "mariadb:11.4@sha256:250e1609e93c0e6aecf64bfbab2d84d5e89d2481540fe77447ff6c855c3b1873",
)
MIGRATION = ROOT / "src/main/resources/db/migration/V378__feed_texto_livre_utf8mb4.sql"

# Colunas que a migracao V378 declara em utf8mb4: (tabela, coluna, nulidade).
COLUNAS = [
    ("posts", "content", "NO"),
    ("posts", "page_html", "YES"),
    ("articles", "body", "NO"),
    ("comments", "content", "NO"),
    ("notifications", "title", "NO"),
    ("notifications", "body", "YES"),
    ("outbox_events", "payload", "NO"),
    ("idempotency_keys", "response_body", "YES"),
    ("ban_content_archive", "content_snapshot", "NO"),
]

# O texto que o editor de artigo produz: travessao triplo, aspas curvas e emoji.
TEXTO_UNICODE = "Artigo ⸻ “aspas” → 🧪 café"


class FeedTextoUtf8mb4MigrationTests(unittest.TestCase):
    @classmethod
    def comando(cls, *args, entrada=None):
        return subprocess.run(
            args,
            input=entrada,
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=True,
            timeout=120,
        ).stdout.strip()

    @classmethod
    def sql(cls, texto):
        return cls.comando(
            "docker", "exec", "-i", cls.container,
            "mariadb", "--default-character-set=utf8mb4", "--batch",
            "--skip-column-names", "-uroot", "feed", entrada=texto,
        )

    @classmethod
    def setUpClass(cls):
        cls.comando("docker", "image", "inspect", IMAGE)
        cls.container = "enois-feed-texto-test-" + uuid.uuid4().hex[:12]
        try:
            cls.comando(
                "docker", "run", "-d", "--pull=never", "--network=none",
                "--name", cls.container,
                "-e", "MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1",
                "-e", "MARIADB_DATABASE=feed",
                "-e", "MARIADB_CHARACTER_SET_SERVER=latin1",
                "-e", "MARIADB_COLLATION_SERVER=latin1_swedish_ci",
                IMAGE,
            )
            for _ in range(90):
                pronto = subprocess.run(
                    ["docker", "exec", cls.container, "healthcheck.sh",
                     "--connect", "--innodb_initialized"],
                    capture_output=True, timeout=15,
                )
                if pronto.returncode == 0:
                    break
                time.sleep(1)
            else:
                raise RuntimeError("MariaDB de teste nao ficou pronto")
            # Schema legado: as tabelas do feed nasceram sem charset e herdaram o default da
            # epoca (latin1 no banco de producao, utf8mb3 na qa).
            cls.sql("""
                create table posts (
                    id varchar(255) not null primary key,
                    content varchar(5000) not null,
                    page_html longtext null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

                create table articles (
                    post_id varchar(255) not null primary key,
                    body varchar(10000) not null,
                    created_at timestamp not null,
                    edited_at timestamp null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

                create table comments (
                    id varchar(255) not null primary key,
                    content varchar(1024) not null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

                create table notifications (
                    id varchar(255) not null primary key,
                    title varchar(500) not null,
                    body varchar(2000) null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

                create table outbox_events (
                    id varchar(255) not null primary key,
                    payload text not null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

                create table idempotency_keys (
                    id bigint auto_increment primary key,
                    response_body text null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

                create table ban_content_archive (
                    id varchar(64) not null primary key,
                    content_snapshot longtext not null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;

                insert into posts values ('post_1', 'Post café', null);
                insert into articles values ('post_1', 'Artigo café', current_timestamp, null);
                insert into comments values ('comment_1', 'Comentário café');
                insert into notifications values ('notif_1', 'Título café', 'Corpo café');
                insert into outbox_events values ('outbox_1', '{"postId":"post_1"}');
                insert into ban_content_archive values ('archive_1', 'Post café');
            """)
            cls.sql(MIGRATION.read_text(encoding="utf-8"))
        except BaseException:
            subprocess.run(["docker", "rm", "-f", "--volumes", cls.container], capture_output=True, timeout=30)
            raise

    @classmethod
    def tearDownClass(cls):
        cls.comando("docker", "rm", "-f", "--volumes", cls.container)

    def test_colunas_de_texto_declaram_utf8mb4(self):
        for tabela, coluna, nulidade in COLUNAS:
            self.assertEqual(
                self.sql(f"""
                    select concat(character_set_name, '|', collation_name, '|', is_nullable)
                    from information_schema.columns
                    where table_schema = 'feed' and table_name = '{tabela}' and column_name = '{coluna}';
                """),
                f"utf8mb4|utf8mb4_unicode_ci|{nulidade}",
                msg=f"{tabela}.{coluna} nao ficou em utf8mb4",
            )

    def test_conteudo_legado_continua_igual(self):
        self.assertEqual(self.sql("select content from posts where id = 'post_1';"), "Post café")
        self.assertEqual(self.sql("select body from articles where post_id = 'post_1';"), "Artigo café")
        self.assertEqual(self.sql("select content from comments where id = 'comment_1';"), "Comentário café")
        self.assertEqual(self.sql("select title from notifications where id = 'notif_1';"), "Título café")
        self.assertEqual(self.sql("select body from notifications where id = 'notif_1';"), "Corpo café")
        self.assertEqual(self.sql("select payload from outbox_events where id = 'outbox_1';"), '{"postId":"post_1"}')
        self.assertEqual(
            self.sql("select content_snapshot from ban_content_archive where id = 'archive_1';"),
            "Post café",
        )

    def test_texto_unicode_entra_no_artigo(self):
        hexadecimal = TEXTO_UNICODE.encode("utf-8").hex()
        self.sql(f"""
            insert into articles values
                ('post_2', convert(unhex('{hexadecimal}') using utf8mb4), current_timestamp, null);
        """)
        self.assertEqual(
            self.sql("select hex(body) from articles where post_id = 'post_2';"),
            hexadecimal.upper(),
        )

    def test_texto_unicode_entra_no_post_e_no_comentario(self):
        hexadecimal = TEXTO_UNICODE.encode("utf-8").hex()
        self.sql(f"""
            insert into posts values ('post_2', convert(unhex('{hexadecimal}') using utf8mb4), null);
            insert into comments values ('comment_2', convert(unhex('{hexadecimal}') using utf8mb4));
        """)
        self.assertEqual(
            self.sql("select hex(content) from posts where id = 'post_2';"),
            hexadecimal.upper(),
        )
        self.assertEqual(
            self.sql("select hex(content) from comments where id = 'comment_2';"),
            hexadecimal.upper(),
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
