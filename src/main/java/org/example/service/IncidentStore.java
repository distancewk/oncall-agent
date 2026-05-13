package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.dto.IncidentRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class IncidentStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncidentStore.class);
    private static final String FILE_SUFFIX = ".json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public IncidentStore(AppIncidentProperties properties, ObjectMapper objectMapper) {
        this.baseDir = Path.of(properties.getPath());
        this.objectMapper = objectMapper;
    }

    public synchronized void save(IncidentRecord record) {
        try {
            Files.createDirectories(baseDir);
            Path target = fileForId(record.getId());
            Path temp = Files.createTempFile(baseDir, record.getId(), ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), record);
            moveIntoPlace(temp, target);
        } catch (IOException e) {
            throw new IllegalStateException("保存 Incident 失败: " + record.getId(), e);
        }
    }

    public synchronized Optional<IncidentRecord> load(String incidentId) {
        Path path = fileForId(incidentId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), IncidentRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("读取 Incident 失败: " + incidentId, e);
        }
    }

    public synchronized List<IncidentRecord> list() {
        if (!Files.exists(baseDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                    .map(this::readIncident)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingLong(IncidentRecord::getUpdatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("读取 Incident 列表失败", e);
        }
    }

    public synchronized Optional<IncidentRecord> findByAggregationKey(String aggregationKey) {
        return list().stream()
                .filter(record -> aggregationKey.equals(record.getAggregationKey()))
                .findFirst();
    }

    private Optional<IncidentRecord> readIncident(Path path) {
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), IncidentRecord.class));
        } catch (IOException e) {
            LOGGER.warn("跳过无法读取的 Incident 文件: {}", path, e);
            return Optional.empty();
        }
    }

    private Path fileForId(String incidentId) {
        if (incidentId == null || !incidentId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("非法 Incident ID: " + incidentId);
        }
        return baseDir.resolve(incidentId + FILE_SUFFIX);
    }

    private void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
