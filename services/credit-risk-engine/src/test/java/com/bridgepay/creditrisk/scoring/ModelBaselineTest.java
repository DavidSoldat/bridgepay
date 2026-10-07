package com.bridgepay.creditrisk.scoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelBaselineTest {

    // sha256("model") starts 9372c470eead
    private static final String MODEL_VERSION = "9372c470eead";

    @TempDir
    Path dir;

    private ModelBaseline load(String modelBytes, String baselineJson) throws Exception {
        Files.writeString(dir.resolve("model.onnx"), modelBytes);
        Files.writeString(dir.resolve("baseline.json"), baselineJson);
        return baseline();
    }

    private ModelBaseline baseline() {
        return new ModelBaseline(new DefaultResourceLoader(), new ObjectMapper(),
                dir.resolve("model.onnx").toUri().toString(), dir.resolve("baseline.json").toUri().toString());
    }

    @Test
    void availableWhenTheHashMatches() throws Exception {
        ModelBaseline b = load("model", "{\"modelVersion\":\"" + MODEL_VERSION + "\",\"aucRoc\":0.82}");
        assertThat(b.content()).isPresent();
        assertThat(b.content().get().get("aucRoc").asDouble()).isEqualTo(0.82);
    }

    @Test
    void unavailableWhenTheModelChanged() throws Exception {
        assertThat(load("retrained model", "{\"modelVersion\":\"" + MODEL_VERSION + "\"}").content()).isEmpty();
    }

    @Test
    void unavailableWhenTheFileIsBrokenOrMissing() throws Exception {
        assertThat(load("model", "{not json").content()).isEmpty();
        Files.delete(dir.resolve("baseline.json"));
        assertThat(baseline().content()).isEmpty();
    }

    @Test
    void theCommittedBaselineMatchesTheCommittedModel() {
        ModelBaseline b = new ModelBaseline(new DefaultResourceLoader(), new ObjectMapper(),
                "classpath:model/model.onnx", "classpath:model/baseline.json");
        assertThat(b.content()).isPresent();
        assertThat(b.content().get().get("scoreBins").size()).isEqualTo(10);
        assertThat(b.content().get().get("features").size()).isEqualTo(10);
    }
}
