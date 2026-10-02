package com.medlearn;

import com.medlearn.platform.config.AnthropicProperties;
import com.medlearn.platform.config.BlobStoreProperties;
import com.medlearn.platform.config.FontProperties;
import com.medlearn.platform.config.TranslationProperties;
import com.medlearn.platform.config.IngestionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        BlobStoreProperties.class,
        IngestionProperties.class,
        FontProperties.class,
        TranslationProperties.class,
        AnthropicProperties.class
})
public class MedTextbookApplication {
    public static void main(String[] args) {
        SpringApplication.run(MedTextbookApplication.class, args);
    }
}
