package cn.reviewsystem.question;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.course.CourseService;
import cn.reviewsystem.material.MaterialChunkSplitter;
import cn.reviewsystem.material.MaterialDtos.MaterialDetail;
import cn.reviewsystem.material.MaterialDtos.MaterialWriteRequest;
import cn.reviewsystem.material.MaterialDtos.PublishRequest;
import cn.reviewsystem.material.MaterialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuestionImportService {

    private final ObjectMapper json;
    private final QuestionValidator validator;
    private final QuestionService questions;
    private final QuestionAssetService assetService;
    private final CourseService courses;
    private final MaterialService materialService;
    private final MaterialChunkSplitter splitter;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;

    public QuestionImportService(
        ObjectMapper json,
        QuestionValidator validator,
        QuestionService questions,
        QuestionAssetService assetService,
        CourseService courses,
        MaterialService materialService,
        MaterialChunkSplitter splitter,
        JdbcTemplate db,
        TransactionTemplate transactions
    ) {
        this.json = json;
        this.validator = validator;
        this.questions = questions;
        this.assetService = assetService;
        this.courses = courses;
        this.materialService = materialService;
        this.splitter = splitter;
        this.db = db;
        this.transactions = transactions;
    }

    public ImportReport importManifest(ImportManifest manifest) {
        return importManifest(manifest, null);
    }

    public ImportReport importManifest(ImportManifest manifest, Path baseDir) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest is required");
        }
        List<ImportQuestion> questionList = manifest.questions() == null ? List.of() : manifest.questions();
        List<ImportMaterial> materialList = manifest.materials() == null ? List.of() : manifest.materials();
        if (questionList.isEmpty() && materialList.isEmpty()) {
            throw new IllegalArgumentException("questions or materials must not both be empty");
        }
        if (!questionList.isEmpty() && (manifest.sourceLabel() == null || manifest.sourceLabel().isBlank())) {
            throw new IllegalArgumentException("sourceLabel is required when importing questions");
        }
        String manifestSource = manifest.sourceLabel() == null ? "" : manifest.sourceLabel().trim();

        // Phase 1: Full validation (no writes)
        Set<String> questionKeys = new HashSet<>();
        Map<Integer, String> questionErrors = new HashMap<>();
        Map<Integer, List<byte[]>> validatedQuestionAssets = new HashMap<>();

        for (int i = 0; i < questionList.size(); i++) {
            ImportQuestion item = questionList.get(i);
            try {
                if (item.sourceQuestionNo() == null || item.sourceQuestionNo().isBlank()) {
                    throw new IllegalArgumentException("sourceQuestionNo is required");
                }
                if (item.origin() != null) {
                    throw new IllegalArgumentException("origin is server controlled and must not be supplied");
                }
                String key = item.sourceQuestionNo().trim() + "\u0000" + item.type();
                if (!questionKeys.add(key)) {
                    throw new IllegalArgumentException("duplicate idempotency key in manifest");
                }
                validator.validate(item.request());
                long kpId = parseId(item.knowledgePointId());
                courses.requireEnabledKnowledgePoint(kpId);

                if (item.assets() != null && !item.assets().isEmpty()) {
                    if (item.assets().size() > QuestionAssetService.MAX_IMAGES) {
                        throw new IllegalArgumentException("A question can have at most " + QuestionAssetService.MAX_IMAGES + " images");
                    }
                    List<byte[]> loadedAssets = new ArrayList<>(item.assets().size());
                    for (ImportAsset asset : item.assets()) {
                        if (asset == null || asset.path() == null || asset.path().isBlank()) {
                            throw new IllegalArgumentException("asset path is required");
                        }
                        Path resolved = resolveAssetPath(baseDir, asset.path().trim());
                        if (!Files.isRegularFile(resolved)) {
                            throw new IllegalArgumentException("asset file not found: " + asset.path());
                        }
                        byte[] bytes = Files.readAllBytes(resolved);
                        assetService.validateImageBytes(bytes, asset.sha256());
                        loadedAssets.add(bytes);
                    }
                    validatedQuestionAssets.put(i, loadedAssets);
                }
            } catch (Exception e) {
                questionErrors.put(i, safe(e));
            }
        }

        Set<String> materialKeys = new HashSet<>();
        Map<Integer, String> materialErrors = new HashMap<>();
        Map<Integer, ValidatedMaterial> validatedMaterials = new HashMap<>();

        for (int i = 0; i < materialList.size(); i++) {
            ImportMaterial item = materialList.get(i);
            try {
                String resolvedSourceLabel = resolveMaterialSourceLabel(item);
                if (resolvedSourceLabel.length() > 300) {
                    throw new IllegalArgumentException("material sourceLabel must not exceed 300 characters");
                }
                if (!materialKeys.add(resolvedSourceLabel)) {
                    throw new IllegalArgumentException("duplicate material idempotency key in manifest: " + resolvedSourceLabel);
                }
                if (item.title() == null || item.title().isBlank()) {
                    throw new IllegalArgumentException("material title is required");
                }
                if (item.title().trim().length() > 200) {
                    throw new IllegalArgumentException("material title must not exceed 200 characters");
                }
                if (item.content() == null || item.content().isBlank()) {
                    throw new IllegalArgumentException("material content must not be blank");
                }
                splitter.split(item.content());
                long kpId = parseId(item.knowledgePointId());
                courses.requireEnabledKnowledgePoint(kpId);

                String normalizedContent = normalizeContent(item.content());
                String normalizedTitle = item.title().trim();

                List<ExistingMaterial> existing = db.query("""
                    SELECT material_id, knowledge_id, title, content, publication_status
                    FROM rs_material_version
                    WHERE subject_id = 1 AND source_label = ?
                    ORDER BY version DESC
                    LIMIT 1
                    """, (rs, rowNum) -> new ExistingMaterial(
                    rs.getLong("material_id"),
                    rs.getLong("knowledge_id"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getString("publication_status")
                ), resolvedSourceLabel);

                boolean skip = false;
                if (!existing.isEmpty()) {
                    ExistingMaterial current = existing.get(0);
                    boolean sameContent = Objects.equals(normalizeContent(current.content()), normalizedContent)
                        && Objects.equals(current.title().trim(), normalizedTitle)
                        && current.knowledgeId() == kpId;
                    if (sameContent) {
                        skip = true;
                    } else {
                        throw new IllegalArgumentException(
                            "material idempotency conflict for " + resolvedSourceLabel
                                + " (status=" + current.publicationStatus() + "): content differs, cannot overwrite"
                        );
                    }
                }

                validatedMaterials.put(i, new ValidatedMaterial(
                    extractFile(item, resolvedSourceLabel, manifestSource),
                    extractLocation(item, resolvedSourceLabel),
                    resolvedSourceLabel,
                    String.valueOf(kpId),
                    normalizedTitle,
                    normalizedContent,
                    Boolean.TRUE.equals(item.publish()),
                    skip
                ));
            } catch (Exception e) {
                materialErrors.put(i, safe(e));
            }
        }

        if (!questionErrors.isEmpty() || !materialErrors.isEmpty()) {
            List<ItemResult> qResults = new ArrayList<>();
            for (int i = 0; i < questionList.size(); i++) {
                ImportQuestion item = questionList.get(i);
                String err = questionErrors.get(i);
                if (err != null) {
                    qResults.add(new ItemResult(
                        item.sourceQuestionNo(), item.type(), "failed", err, null,
                        manifestSource, item.sourceQuestionNo()
                    ));
                } else {
                    qResults.add(new ItemResult(
                        item.sourceQuestionNo(), item.type(), "rolled_back", "aborted due to batch validation errors", null,
                        manifestSource, item.sourceQuestionNo()
                    ));
                }
            }
            List<MaterialItemResult> mResults = new ArrayList<>();
            for (int i = 0; i < materialList.size(); i++) {
                ImportMaterial item = materialList.get(i);
                String label = safeLabel(item);
                String file = extractFile(item, label, manifestSource);
                String loc = extractLocation(item, label);
                String err = materialErrors.get(i);
                if (err != null) {
                    mResults.add(new MaterialItemResult(file, loc, label, "failed", err, null));
                } else {
                    mResults.add(new MaterialItemResult(file, loc, label, "rolled_back", "aborted due to batch validation errors", null));
                }
            }
            int failedCount = questionErrors.size() + materialErrors.size();
            return new ImportReport(
                manifestSource,
                questionList.size() + materialList.size(),
                0,
                0,
                failedCount,
                qResults,
                mResults
            );
        }

        // Phase 2: Single transaction write
        try {
            return transactions.execute(status -> {
                List<ItemResult> qResults = new ArrayList<>();
                List<MaterialItemResult> mResults = new ArrayList<>();
                int imported = 0;
                int skipped = 0;

                for (int i = 0; i < questionList.size(); i++) {
                    ImportQuestion item = questionList.get(i);
                    Integer exists = db.queryForObject(
                        "SELECT count(*) FROM rs_question_import_item WHERE source_label=? AND source_question_no=? AND question_type=?",
                        Integer.class, manifestSource, item.sourceQuestionNo().trim(), item.type()
                    );
                    if (exists != null && exists > 0) {
                        skipped++;
                        qResults.add(new ItemResult(
                            item.sourceQuestionNo(), item.type(), "skipped", "already imported", null,
                            manifestSource, item.sourceQuestionNo()
                        ));
                        continue;
                    }
                    AdminQuestionDetail created = questions.create(item.request());
                    long qId = Long.parseLong(created.id());
                    List<byte[]> assetBytes = validatedQuestionAssets.get(i);
                    if (assetBytes != null && !assetBytes.isEmpty()) {
                        Long versionId = db.queryForObject(
                            "SELECT current_version_id FROM rs_question WHERE id=?",
                            Long.class, qId
                        );
                        for (byte[] bytes : assetBytes) {
                            assetService.storeFromBytes(versionId, bytes);
                        }
                    }
                    db.update(
                        "INSERT INTO rs_question_import_item(source_label,source_question_no,question_type,question_id) VALUES (?,?,?,?)",
                        manifestSource, item.sourceQuestionNo().trim(), item.type(), qId
                    );
                    imported++;
                    qResults.add(new ItemResult(
                        item.sourceQuestionNo(), item.type(), "imported", null, created.id(),
                        manifestSource, item.sourceQuestionNo()
                    ));
                }

                for (int i = 0; i < materialList.size(); i++) {
                    ValidatedMaterial vm = validatedMaterials.get(i);
                    if (vm.skip()) {
                        skipped++;
                        mResults.add(new MaterialItemResult(
                            vm.file(), vm.location(), vm.sourceLabel(), "skipped", "already imported with identical content", null
                        ));
                        continue;
                    }
                    MaterialDetail created = materialService.create(new MaterialWriteRequest(
                        vm.knowledgePointId(),
                        vm.title(),
                        vm.sourceLabel(),
                        vm.content(),
                        null
                    ));
                    if (vm.publish()) {
                        created = materialService.publish(
                            Long.parseLong(created.materialId()),
                            new PublishRequest(created.latestVersion())
                        );
                    }
                    imported++;
                    mResults.add(new MaterialItemResult(
                        vm.file(), vm.location(), vm.sourceLabel(), "imported", null, created.materialId()
                    ));
                }

                return new ImportReport(
                    manifestSource,
                    questionList.size() + materialList.size(),
                    imported,
                    skipped,
                    0,
                    qResults,
                    mResults
                );
            });
        } catch (Exception e) {
            String err = safe(e);
            List<ItemResult> qResults = questionList.stream()
                .map(q -> new ItemResult(q.sourceQuestionNo(), q.type(), "failed", err, null, manifestSource, q.sourceQuestionNo()))
                .toList();
            List<MaterialItemResult> mResults = materialList.stream()
                .map(m -> {
                    String label = safeLabel(m);
                    return new MaterialItemResult(extractFile(m, label, manifestSource), extractLocation(m, label), label, "failed", err, null);
                })
                .toList();
            return new ImportReport(
                manifestSource,
                questionList.size() + materialList.size(),
                0,
                0,
                questionList.size() + materialList.size(),
                qResults,
                mResults
            );
        }
    }

    public ImportReport importFile(Path manifest, Path report) throws IOException {
        Path absManifest = manifest.toAbsolutePath().normalize();
        ImportManifest parsed = json.readValue(absManifest.toFile(), ImportManifest.class);
        ImportReport result = importManifest(parsed, absManifest.getParent());
        Files.writeString(
            report,
            json.writerWithDefaultPrettyPrinter().writeValueAsString(result),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
        return result;
    }

    private static Path resolveAssetPath(Path baseDir, String rawPath) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute() || baseDir == null) {
            return path.normalize();
        }
        Path resolved = baseDir.resolve(path).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            throw new IllegalArgumentException("asset path escapes manifest directory: " + rawPath);
        }
        return resolved;
    }

    private static String resolveMaterialSourceLabel(ImportMaterial item) {
        if (item == null) {
            throw new IllegalArgumentException("material item must not be null");
        }
        if (item.sourceLabel() != null && !item.sourceLabel().isBlank()) {
            return item.sourceLabel().trim();
        }
        if (item.sourceFile() != null && !item.sourceFile().isBlank()
            && item.location() != null && !item.location().isBlank()) {
            String file = item.sourceFile().trim();
            String loc = item.location().trim();
            return loc.startsWith("#") ? file + loc : file + "#" + loc;
        }
        throw new IllegalArgumentException("material sourceLabel (or sourceFile + location) is required");
    }

    private static String safeLabel(ImportMaterial item) {
        if (item == null) {
            return "";
        }
        if (item.sourceLabel() != null && !item.sourceLabel().isBlank()) {
            return item.sourceLabel().trim();
        }
        if (item.sourceFile() != null && item.location() != null) {
            String file = item.sourceFile().trim();
            String loc = item.location().trim();
            return loc.startsWith("#") ? file + loc : file + "#" + loc;
        }
        return item.sourceFile() != null ? item.sourceFile().trim() : "";
    }

    private static String extractFile(ImportMaterial item, String label, String fallback) {
        if (item != null && item.sourceFile() != null && !item.sourceFile().isBlank()) {
            return item.sourceFile().trim();
        }
        int idx = label.indexOf('#');
        if (idx > 0) {
            return label.substring(0, idx);
        }
        return fallback;
    }

    private static String extractLocation(ImportMaterial item, String label) {
        if (item != null && item.location() != null && !item.location().isBlank()) {
            return item.location().trim();
        }
        int idx = label.indexOf('#');
        if (idx >= 0 && idx + 1 < label.length()) {
            return label.substring(idx + 1);
        }
        return label;
    }

    private static String normalizeContent(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("knowledgePointId is required");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid knowledgePointId: " + raw);
        }
    }

    private static String safe(Exception e) {
        if (!(e instanceof BusinessException) && !(e instanceof IllegalArgumentException)) {
            return "database write failed";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "validation failed";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    public record ImportManifest(
        String sourceLabel,
        List<ImportQuestion> questions,
        List<ImportMaterial> materials
    ) {
        public ImportManifest(String sourceLabel, List<ImportQuestion> questions) {
            this(sourceLabel, questions, List.of());
        }
    }

    public record ImportAsset(String path, String sha256) {}

    public record ImportQuestion(
        String sourceQuestionNo,
        String knowledgePointId,
        String type,
        JsonNode content,
        JsonNode answer,
        JsonNode rubric,
        String explanation,
        String sourceRef,
        String origin,
        Double maxScore,
        List<ImportAsset> assets
    ) {
        public ImportQuestion(
            String sourceQuestionNo,
            String knowledgePointId,
            String type,
            JsonNode content,
            JsonNode answer,
            JsonNode rubric,
            String explanation,
            String sourceRef,
            String origin,
            Double maxScore
        ) {
            this(sourceQuestionNo, knowledgePointId, type, content, answer, rubric, explanation, sourceRef, origin, maxScore, List.of());
        }

        QuestionWriteRequest request() {
            return new QuestionWriteRequest(knowledgePointId, type, content, answer, rubric, explanation, sourceRef, null, maxScore, null);
        }
    }

    public record ImportMaterial(
        String sourceFile,
        String location,
        String sourceLabel,
        String knowledgePointId,
        String title,
        String content,
        Boolean publish
    ) {}

    public record ItemResult(
        String sourceQuestionNo,
        String type,
        String status,
        String message,
        String questionId,
        String file,
        String location
    ) {
        public ItemResult(String sourceQuestionNo, String type, String status, String message, String questionId) {
            this(sourceQuestionNo, type, status, message, questionId, null, sourceQuestionNo);
        }
    }

    public record MaterialItemResult(
        String file,
        String location,
        String sourceLabel,
        String status,
        String message,
        String materialId
    ) {}

    public record ImportReport(
        String sourceLabel,
        int total,
        int imported,
        int skipped,
        int failed,
        List<ItemResult> items,
        List<MaterialItemResult> materialItems
    ) {
        public ImportReport(String sourceLabel, int total, int imported, int skipped, int failed, List<ItemResult> items) {
            this(sourceLabel, total, imported, skipped, failed, items, List.of());
        }

        public boolean successful() {
            return failed == 0;
        }
    }

    private record ExistingMaterial(long materialId, long knowledgeId, String title, String content, String publicationStatus) {}

    private record ValidatedMaterial(
        String file,
        String location,
        String sourceLabel,
        String knowledgePointId,
        String title,
        String content,
        boolean publish,
        boolean skip
    ) {}
}
