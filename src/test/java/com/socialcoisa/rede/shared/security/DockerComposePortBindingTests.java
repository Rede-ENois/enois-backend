package com.socialcoisa.rede.shared.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Esse docker-compose.yml também roda em servidores com IP público
 * (veja docs/DEPLOYMENT.md).
 *
 * O problema é que, quando a gente publica uma porta sem definir o IP
 * de bind, como em "3306:3306", o Docker escuta em 0.0.0.0.
 * Se não tiver um firewall bloqueando, o banco pode ficar exposto
 * na internet.
 *
 * Então é melhor limitar a porta a 127.0.0.1 ou simplesmente
 * não publicar. A aplicação já consegue acessar o banco pela
 * rede interna do Compose, sem precisar expor essa porta.
 */
class DockerComposePortBindingTests {

    private static final Set<String> DATABASE_SERVICES = Set.of("mariadb", "mysql", "postgres", "redis", "mongo");

    @Test
    void databaseServicesOnlyPublishPortsOnLoopback() throws IOException {
        Path composeFile = Path.of(System.getProperty("user.dir"), "docker-compose.yml");
        assertThat(composeFile).as("docker-compose.yml deve existir na raiz do projeto").exists();

        Map<String, Object> root = loadYaml(composeFile);
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        assertThat(services).as("docker-compose.yml deve declarar services").isNotNull();

        for (String serviceName : DATABASE_SERVICES) {
            if (!services.containsKey(serviceName)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> service = (Map<String, Object>) services.get(serviceName);
            @SuppressWarnings("unchecked")
            List<String> ports = (List<String>) service.get("ports");
            if (ports == null) {
                continue;
            }
            for (String portMapping : ports) {
                assertThat(portMapping)
                        .as("serviço '%s' publica porta '%s' sem restringir a 127.0.0.1 (fica acessível em 0.0.0.0)",
                                serviceName, portMapping)
                        .startsWith("127.0.0.1:");
            }
        }
    }

    private Map<String, Object> loadYaml(Path file) throws IOException {
        try (InputStream in = new FileInputStream(file.toFile())) {
            return new Yaml().load(in);
        }
    }
}
