package com.dadacore.lore.web;

import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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
        return new OpenAPI().info(new Info()
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
