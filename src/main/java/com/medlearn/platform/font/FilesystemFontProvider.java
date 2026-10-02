package com.medlearn.platform.font;

import com.medlearn.platform.config.FontProperties;
import com.medlearn.shared.LanguageTag;
import com.medlearn.shared.Script;
import com.medlearn.shared.port.FontProviderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds a font on disk that covers the target language's script.
 *
 * <p>Fonts are not bundled in the jar. Latin/Cyrillic/Greek coverage is a megabyte, but a
 * CJK face is 20MB or more per weight, and shipping those to everyone to serve the rare
 * Chinese translation is the wrong trade. Instead the configured {@code medlearn.fonts.dir}
 * is searched first, then the usual system font directories.
 *
 * <p>When nothing matches, resolution returns empty and the caller reports
 * {@code DomainError.FontUnavailable} naming the directories searched. That matters because
 * the alternative is silent: a PDF rendered with a font missing the glyphs shows blank
 * boxes, raises no error, and is only discovered by a human opening the file.
 */
@Component
public class FilesystemFontProvider implements FontProviderPort {

    private static final Logger log = LoggerFactory.getLogger(FilesystemFontProvider.class);

    /**
     * Preferred file-name fragments per script, best first. Noto is preferred for its
     * coverage; DejaVu is a dependable fallback present on most Linux systems and covering
     * Latin, Cyrillic and Greek - but not CJK.
     */
    private static final List<String> LATIN_LIKE_REGULAR =
            List.of("notosans-regular", "notoserif-regular", "dejavusans.ttf", "dejavuserif.ttf",
                    "liberationsans-regular", "arial.ttf");
    private static final List<String> LATIN_LIKE_BOLD =
            List.of("notosans-bold", "notoserif-bold", "dejavusans-bold", "dejavuserif-bold",
                    "liberationsans-bold", "arialbd.ttf");
    private static final List<String> CJK_REGULAR =
            List.of("notosanscjk", "notoserifcjk", "sourcehansans", "notosanssc", "notosansjp",
                    "notosanskr", "msyh", "simsun", "hiraginosans");
    private static final List<String> CJK_BOLD =
            List.of("notosanscjk-bold", "notosanscjk", "sourcehansans-bold", "notosanscjk");

    private final FontProperties properties;

    public FilesystemFontProvider(FontProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<Path> regularFont(LanguageTag language) {
        return find(language.script() == Script.CJK ? CJK_REGULAR : LATIN_LIKE_REGULAR);
    }

    @Override
    public Optional<Path> boldFont(LanguageTag language) {
        Optional<Path> bold = find(language.script() == Script.CJK ? CJK_BOLD : LATIN_LIKE_BOLD);
        // Falling back to the regular weight is far better than failing to render: a heading
        // that is not bold is a cosmetic flaw, a missing heading is a content loss.
        return bold.isPresent() ? bold : regularFont(language);
    }

    @Override
    public String searchLocationsDescription() {
        List<String> locations = new ArrayList<>();
        locations.add(properties.dir().toAbsolutePath().toString());
        properties.systemFallback().forEach(path -> locations.add(path.toString()));
        return String.join(", ", locations);
    }

    private Optional<Path> find(List<String> preferredFragments) {
        List<Path> candidates = candidateFiles();
        for (String fragment : preferredFragments) {
            for (Path candidate : candidates) {
                String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.contains(fragment)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Configured directory first, so a user-supplied font always wins over a system one. */
    private List<Path> candidateFiles() {
        List<Path> roots = new ArrayList<>();
        roots.add(properties.dir());
        roots.addAll(properties.systemFallback());

        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root, 2)) {
                walk.filter(Files::isRegularFile)
                        .filter(FilesystemFontProvider::isFontFile)
                        .forEach(files::add);
            } catch (IOException e) {
                log.debug("Could not scan font directory {}: {}", root, e.getMessage());
            }
        }
        return files;
    }

    private static boolean isFontFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        // .ttc collections are deliberately excluded: PDFBox needs an index into them, and
        // picking the wrong face silently yields the wrong script.
        return name.endsWith(".ttf") || name.endsWith(".otf");
    }
}
