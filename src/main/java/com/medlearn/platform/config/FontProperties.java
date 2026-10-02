package com.medlearn.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * @param dir            directory searched first for embeddable fonts
 * @param systemFallback additional directories searched if {@code dir} has nothing suitable
 */
@ConfigurationProperties(prefix = "medlearn.fonts")
public record FontProperties(Path dir, List<Path> systemFallback) {
    public FontProperties {
        if (dir == null) dir = Path.of("./fonts");
        if (systemFallback == null || systemFallback.isEmpty()) {
            systemFallback = List.of(
                    Path.of("/usr/share/fonts/truetype/dejavu"),
                    Path.of("/usr/share/fonts/truetype"),
                    Path.of("/Library/Fonts"),
                    Path.of("C:/Windows/Fonts"));
        }
    }
}
