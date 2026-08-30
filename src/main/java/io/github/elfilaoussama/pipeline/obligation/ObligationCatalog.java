package io.github.elfilaoussama.pipeline.obligation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;

public final class ObligationCatalog {
    private final List<ObligationDefinition> obligations;

    private ObligationCatalog(List<ObligationDefinition> obligations) {
        this.obligations = obligations.stream().sorted(Comparator.comparing(ObligationDefinition::id)).toList();
    }

    public static ObligationCatalog load() {
        try (InputStream input = ObligationCatalog.class.getResourceAsStream("/obligations/catalog.json")) {
            if (input == null) {
                throw new IllegalStateException("bundled obligation catalog is missing");
            }
            CatalogFile file = new ObjectMapper().readValue(input, CatalogFile.class);
            if (!"1".equals(file.schemaVersion()) || file.obligations() == null || file.obligations().isEmpty()) {
                throw new IllegalStateException("unsupported or empty obligation catalog");
            }
            return new ObligationCatalog(file.obligations());
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load obligation catalog", failure);
        }
    }

    public List<ObligationDefinition> all() {
        return obligations;
    }

    public ObligationDefinition require(String id) {
        return obligations.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown obligation: " + id));
    }

    private record CatalogFile(String schemaVersion, List<ObligationDefinition> obligations) {
    }
}
