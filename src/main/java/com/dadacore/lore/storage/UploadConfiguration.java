package com.dadacore.lore.storage;

import com.dadacore.lore.LoreProperties;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * The files being received are written to the temporary folder of the files, on the same disk as
 * their final place, whether {@code LORE_FILES_PATH} is absolute or relative.
 */
@Configuration
class UploadConfiguration {

    @Bean
    MultipartConfigElement multipartConfigElement(MultipartProperties properties, FileStore store, LoreProperties lore) {
        properties.setLocation(store.temporaryFolder().toString());
        // A request is never larger than a file at the ceiling and its fields.
        properties.setMaxRequestSize(DataSize.ofBytes(lore.maxUploadSize().toBytes() + LoreProperties.UPLOAD_FORM_MARGIN.toBytes()));
        return properties.createMultipartConfig();
    }
}
