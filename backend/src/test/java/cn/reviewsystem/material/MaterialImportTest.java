package cn.reviewsystem.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.reviewsystem.question.QuestionImportService;
import cn.reviewsystem.question.QuestionImportService.ImportAsset;
import cn.reviewsystem.question.QuestionImportService.ImportManifest;
import cn.reviewsystem.question.QuestionImportService.ImportMaterial;
import cn.reviewsystem.question.QuestionImportService.ImportQuestion;
import cn.reviewsystem.question.QuestionImportService.ImportReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MaterialImportTest {

    @Autowired QuestionImportService importService;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;

    @TempDir Path tempDir;

    private long knowledgeId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long chapterId = db.queryForObject(
            "INSERT INTO rs_chapter(subject_id, code, name, sort_order) VALUES (1, ?, ?, 97) RETURNING id",
            Long.class,
            "I8" + suffix.substring(0, 6),
            "Import Chapter " + suffix
        );
        knowledgeId = db.queryForObject(
            "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order) VALUES (1, ?, ?, ?, 1) RETURNING id",
            Long.class, chapterId, "IK-" + suffix.substring(0, 6), "Import KP " + suffix
        );
    }

    @Test
    void idempotentMaterialAndAssetQuestionImport() throws Exception {
        byte[] pngBytes = createPngBytes();
        String sha = sha256(pngBytes);
        Path imgPath = tempDir.resolve("q1.png");
        Files.write(imgPath, pngBytes);

        ImportQuestion q1 = new ImportQuestion(
            "Q-IMP-1",
            String.valueOf(knowledgeId),
            "CHOICE",
            json.valueToTree(Map.of(
                "stem", "带图题干",
                "options", List.of(
                    Map.of("key", "A", "text", "选项A"),
                    Map.of("key", "B", "text", "选项B"),
                    Map.of("key", "C", "text", "选项C")
                )
            )),
            json.valueToTree(Map.of("correctOption", "A")),
            null,
            "解析",
            "19A.pdf#q1",
            null,
            2.0,
            List.of(new ImportAsset("q1.png", sha))
        );

        ImportMaterial m1 = new ImportMaterial(
            "19A.pdf",
            "p12",
            null,
            String.valueOf(knowledgeId),
            "栈的应用讲义",
            "栈用于括号匹配与表达式求值。",
            true
        );

        ImportManifest manifest = new ImportManifest("19A.pdf", List.of(q1), List.of(m1));

        ImportReport first = importService.importManifest(manifest, tempDir);
        assertTrue(first.successful());
        assertEquals(2, first.imported());
        assertEquals(0, first.skipped());

        // Second run with same manifest -> all skipped
        ImportReport second = importService.importManifest(manifest, tempDir);
        assertTrue(second.successful());
        assertEquals(0, second.imported());
        assertEquals(2, second.skipped());

        // Same key (19A.pdf#p12) with different content -> conflict failure, does not overwrite published version
        ImportMaterial conflicting = new ImportMaterial(
            "19A.pdf",
            "p12",
            null,
            String.valueOf(knowledgeId),
            "栈的应用讲义",
            "修改后的不同内容",
            true
        );
        ImportReport conflictReport = importService.importManifest(
            new ImportManifest("19A.pdf", List.of(), List.of(conflicting)),
            tempDir
        );
        assertFalse(conflictReport.successful());
        assertEquals(1, conflictReport.failed());
        assertEquals("19A.pdf", conflictReport.materialItems().get(0).file());
        assertEquals("p12", conflictReport.materialItems().get(0).location());
        String savedContent = db.queryForObject(
            "SELECT content FROM rs_material_version WHERE source_label='19A.pdf#p12' ORDER BY version DESC LIMIT 1",
            String.class
        );
        assertEquals("栈用于括号匹配与表达式求值。", savedContent);
    }

    @Test
    void failedBatchRollsBackAllWritesLeavingCountsUnchanged() throws Exception {
        byte[] pngBytes = createPngBytes();
        Path imgPath = tempDir.resolve("bad-sha.png");
        Files.write(imgPath, pngBytes);

        long qBefore = count("rs_question");
        long assetBefore = count("rs_question_asset");
        long matBefore = count("rs_material_version");
        long chunkBefore = count("rs_material_chunk");

        ImportMaterial validMaterial = new ImportMaterial(
            "20B.pdf", "p1", null, String.valueOf(knowledgeId), "有效资料", "有效正文", true
        );
        ImportQuestion badShaQuestion = new ImportQuestion(
            "Q-BAD-SHA",
            String.valueOf(knowledgeId),
            "CHOICE",
            json.valueToTree(Map.of(
                "stem", "题干",
                "options", List.of(
                    Map.of("key", "A", "text", "1"),
                    Map.of("key", "B", "text", "2"),
                    Map.of("key", "C", "text", "3")
                )
            )),
            json.valueToTree(Map.of("correctOption", "A")),
            null,
            "解析",
            "20B.pdf#q1",
            null,
            2.0,
            List.of(new ImportAsset("bad-sha.png", "0".repeat(64)))
        );

        ImportReport report = importService.importManifest(
            new ImportManifest("20B.pdf", List.of(badShaQuestion), List.of(validMaterial)),
            tempDir
        );

        assertFalse(report.successful());
        assertEquals(0, report.imported());
        assertEquals(1, report.failed());
        assertEquals(qBefore, count("rs_question"));
        assertEquals(assetBefore, count("rs_question_asset"));
        assertEquals(matBefore, count("rs_material_version"));
        assertEquals(chunkBefore, count("rs_material_chunk"));
    }

    private long count(String table) {
        Long value = db.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private static byte[] createPngBytes() throws Exception {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
