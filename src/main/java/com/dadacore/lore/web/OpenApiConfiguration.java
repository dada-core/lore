package com.dadacore.lore.web;

import java.util.List;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Description of the REST API, served at {@code /api/v1/openapi.json}. The interface's
 * TypeScript types are generated from it ({@code npm run api} in {@code web/}).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI loreOpenApi() {
        // Each Lore instance serves the API at its own address: a relative server keeps the description
        // the same everywhere, instead of the address of the request that generated it.
        return new OpenAPI().servers(List.of(new Server().url("/"))).info(new Info()
                .title("Lore API")
                .version("v1")
                .description("Every action of the Lore interface goes through this API.")
                .license(new License()
                        .name("AGPL-3.0")
                        .url("https://www.gnu.org/licenses/agpl-3.0.html")));
    }

    @Bean
    ModelConverter recordSchemas() {
        return new RecordSchemas();
    }
}
