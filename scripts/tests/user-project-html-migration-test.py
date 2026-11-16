#!/usr/bin/env python3
"""Verifica o upgrade do HTML legado em MariaDB, sem porta publicada nem rede."""

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
MIGRATION = ROOT / "src/main/resources/db/migration/V287__user_project_html_charset_upgrade.sql"


class UserProjectHtmlMigrationTests(unittest.TestCase):
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
            "--skip-column-names", "-uroot", "projetos", entrada=texto,
        )

    @classmethod
    def setUpClass(cls):
        cls.comando("docker", "image", "inspect", IMAGE)
        cls.container = "enois-project-html-test-" + uuid.uuid4().hex[:12]
        try:
            cls.comando(
                "docker", "run", "-d", "--pull=never", "--network=none",
                "--name", cls.container,
                "-e", "MARIADB_ALLOW_EMPTY_ROOT_PASSWORD=1",
                "-e", "MARIADB_DATABASE=projetos", IMAGE,
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
            cls.sql("""
                create table user_projects (
                    id varchar(40) not null primary key,
                    user_id varchar(40) not null,
                    html longtext character set latin1 collate latin1_swedish_ci null
                ) engine=InnoDB default charset=latin1 collate=latin1_swedish_ci;
                insert into user_projects values
                    ('legado', 'dono_original', '<p>Projeto café</p>'),
                    ('sem_html', 'outro_dono', null);
            """)
            cls.sql(MIGRATION.read_text(encoding="utf-8"))
        except BaseException:
            subprocess.run(["docker", "rm", "-f", "--volumes", cls.container], capture_output=True, timeout=30)
            raise

    @classmethod
    def tearDownClass(cls):
        cls.comando("docker", "rm", "-f", "--volumes", cls.container)

    def test_coluna_declara_longtext_unicode_e_aceita_nulo(self):
        self.assertEqual(self.sql("""
            select concat(data_type, '|', character_set_name, '|', collation_name, '|', is_nullable)
            from information_schema.columns
            where table_schema = 'projetos' and table_name = 'user_projects' and column_name = 'html';
        """), "longtext|utf8mb4|utf8mb4_unicode_ci|YES")

    def test_preserva_conteudo_nulo_e_identificadores_existentes(self):
        self.assertEqual(self.sql("""
            select count(*) from user_projects
            where (id = 'legado' and user_id = 'dono_original' and hex(html) = hex('<p>Projeto café</p>'))
               or (id = 'sem_html' and user_id = 'outro_dono' and html is null);
        """), "2")

    def test_cria_e_edita_html_unicode_sem_truncar(self):
        html = '<style>.marca::before { content: "✳"; }</style><h2>Projeto → ↗ ⌕ ◎ 🧪</h2>'
        hexadecimal = html.encode("utf-8").hex()
        self.sql(f"""
            insert into user_projects values
                ('unicode', 'dono_unicode', convert(unhex('{hexadecimal}') using utf8mb4));
        """)
        self.assertEqual(self.sql("select hex(html) from user_projects where id = 'unicode';"), hexadecimal.upper())
        html_longo = "<p>" + ("Conteudo → 🧪 " * 6000) + "</p>"
        self.assertGreater(len(html_longo.encode("utf-8")), 65535)
        self.assertLess(len(html_longo), 200000)
        hexadecimal_longo = html_longo.encode("utf-8").hex()
        self.sql(f"""
            update user_projects set html = convert(unhex('{hexadecimal_longo}') using utf8mb4)
            where id = 'unicode';
        """)
        self.assertEqual(self.sql("select hex(html) from user_projects where id = 'unicode';"), hexadecimal_longo.upper())


if __name__ == "__main__":
    unittest.main(verbosity=2)
