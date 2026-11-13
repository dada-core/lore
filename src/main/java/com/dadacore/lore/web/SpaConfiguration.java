package com.dadacore.lore.web;

import java.io.IOException;
import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the web interface built by Vite ({@code web/dist}, packaged under {@code static/}).
 *
 * <p>Every address of the interface ({@code /login}, {@code /admin/members}...) returns
 * {@code index.html}, and the interface's router displays the right view. Addresses under
 * {@code /api/} and {@code /actuator/} are never answered with the interface.
 */
@Configuration(proxyBeanMethods = false)
class SpaConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // File names contain a hash of their content: they can be cached for a year.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new InterfaceResourceResolver());
    }

    static final class InterfaceResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null) {
                return resource;
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
                    || resourcePath.contains(".")) {
                return null;
            }
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }
    }
}
