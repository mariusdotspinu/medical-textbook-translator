package com.medlearn.ingestion.adapter.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medlearn.ingestion.domain.TextBlock;
import com.medlearn.platform.compress.ZstdCodec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Serialises a page's text blocks to a compact binary column value.
 *
 * <p>Only the blocks are encoded here. Page number, dimensions, kind and counts live in
 * real columns so they stay queryable; duplicating them inside the blob would cost space
 * and allow the two copies to disagree.
 *
 * <p>Uses <b>Jackson 2</b> with its own explicitly constructed mapper rather than Spring's
 * autoconfigured one. Two reasons: Spring Boot 4 serves web payloads with Jackson 3
 * ({@code tools.jackson}), and this is a stored binary format whose shape must not drift
 * because someone retunes the web mapper.
 */
@Component
public class LayoutCodec {

    private static final TypeReference<List<TextBlock>> BLOCK_LIST = new TypeReference<>() {};

    private final ObjectMapper mapper = new ObjectMapper()
            // Tolerate blocks written by an older build that lacked a field.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public byte[] encode(List<TextBlock> blocks) {
        try {
            return ZstdCodec.compress(mapper.writeValueAsBytes(blocks));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode page layout", e);
        }
    }

    public List<TextBlock> decode(byte[] stored) {
        try {
            return mapper.readValue(ZstdCodec.decompress(stored), BLOCK_LIST);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode page layout", e);
        }
    }
}
