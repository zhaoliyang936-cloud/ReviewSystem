package cn.reviewsystem.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.reviewsystem.security.IdentityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MaterialIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired IdentityService identity;
    @Autowired MaterialSearchPort searchPort;

    private Cookie adminCookie;
    private Cookie studentCookie;
    private long adminUserId;
    private long chapterId;
    private long knowledgeId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String adminName = "m08_adm_" + suffix;
        String studentName = "m08_stu_" + suffix;
        identity.register(adminName, "Password12345!");
        db.update("UPDATE rs_user SET role='ADMIN' WHERE username=?", adminName);
        var adminUser = identity.find(adminName);
        adminUserId = adminUser.id();
        adminCookie = new Cookie("RSID", identity.newSession(adminUser));

        identity.register(studentName, "Password12345!");
        studentCookie = new Cookie("RSID", identity.newSession(identity.find(studentName)));

        chapterId = db.queryForObject(
            "INSERT INTO rs_chapter(subject_id, code, name, sort_order) VALUES (1, ?, ?, 99) RETURNING id",
            Long.class,
            "C8" + suffix.substring(0, 6),
            "M08 Chapter " + suffix
        );
        knowledgeId = db.queryForObject(
            "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order) VALUES (1, ?, ?, ?, 1) RETURNING id",
            Long.class,
            chapterId,
            "K8-" + suffix.substring(0, 6),
            "M08 KP " + suffix
        );
    }

    @Test
    void draftPublishSplitAndWithdrawLifecycle() throws Exception {
        // Create draft without sourceLabel -> 201
        String createBody = json.writeValueAsString(Map.of(
            "knowledgeId", String.valueOf(knowledgeId),
            "title", "二叉树遍历基础",
            "content", "第一段内容。\n\n" + "长句子重复。".repeat(120)
        ));
        String createdJson = mvc.perform(post("/api/admin/materials")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.publicationStatus").value("DRAFT"))
            .andExpect(jsonPath("$.latestVersion").value(1))
            .andReturn().getResponse().getContentAsString();
        long materialId = json.readTree(createdJson).get("materialId").asLong();

        // Publish without sourceLabel -> 400
        mvc.perform(post("/api/admin/materials/" + materialId + "/publish")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 1))))
            .andExpect(status().isBadRequest());

        // Update to v2 with sourceLabel -> 200
        String updateBody = json.writeValueAsString(Map.of(
            "knowledgeId", String.valueOf(knowledgeId),
            "title", "二叉树遍历基础",
            "sourceLabel", "19A.pdf#p12",
            "content", "第一段：前序遍历先访问根节点。\n\n" + "第二段长文本包含很多节点说明。".repeat(100),
            "expectedVersion", 1
        ));
        mvc.perform(put("/api/admin/materials/" + materialId)
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestVersion").value(2))
            .andExpect(jsonPath("$.versionCount").value(2))
            .andExpect(jsonPath("$.publicationStatus").value("DRAFT"));

        // Publish v2 -> 200, chunks split cleanly 0..n-1 and <= 1000 chars each
        String pubJson = mvc.perform(post("/api/admin/materials/" + materialId + "/publish")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 2))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicationStatus").value("PUBLISHED"))
            .andExpect(jsonPath("$.approvedBy").value(String.valueOf(adminUserId)))
            .andReturn().getResponse().getContentAsString();

        JsonNode pubNode = json.readTree(pubJson);
        assertNotNull(pubNode.get("approvedAt").asText());
        JsonNode chunks = pubNode.get("chunks");
        assertTrue(chunks.size() >= 2, "Expected long paragraph to be split into multiple chunks");
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).get("position").asInt());
            String text = chunks.get(i).get("text").asText();
            assertTrue(!text.isBlank() && text.length() <= 1000, "Chunk length out of bounds: " + text.length());
        }

        // Verify list endpoint
        mvc.perform(get("/api/admin/materials")
                .param("knowledgeId", String.valueOf(knowledgeId))
                .param("status", "PUBLISHED")
                .cookie(adminCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].materialId").value(String.valueOf(materialId)));

        // Withdraw v2 -> 200
        mvc.perform(post("/api/admin/materials/" + materialId + "/withdraw")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "内容过时", "expectedVersion", 2))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.publicationStatus").value("WITHDRAWN"))
            .andExpect(jsonPath("$.withdrawReason").value("内容过时"));
    }

    @Test
    void editingLeavesOldVersionImmutableAndRejectsVersionConflicts() throws Exception {
        String createBody = json.writeValueAsString(Map.of(
            "knowledgeId", String.valueOf(knowledgeId),
            "title", "原版标题",
            "sourceLabel", "notes.pdf#p1",
            "content", "原版正文内容"
        ));
        long materialId = json.readTree(
            mvc.perform(post("/api/admin/materials")
                    .cookie(adminCookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
        ).get("materialId").asLong();

        // Update with expectedVersion=1 -> creates v2
        mvc.perform(put("/api/admin/materials/" + materialId)
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "knowledgeId", String.valueOf(knowledgeId),
                    "title", "新版标题",
                    "sourceLabel", "notes.pdf#p2",
                    "content", "新版正文内容",
                    "expectedVersion", 1
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestVersion").value(2));

        // Verify v1 row in DB is untouched
        Map<String, Object> v1Row = db.queryForMap(
            "SELECT title, content, publication_status FROM rs_material_version WHERE material_id=? AND version=1",
            materialId
        );
        assertEquals("原版标题", v1Row.get("title"));
        assertEquals("原版正文内容", v1Row.get("content"));
        assertEquals("DRAFT", v1Row.get("publication_status"));

        // Stale expectedVersion=1 on update -> 409 STATE_CONFLICT
        mvc.perform(put("/api/admin/materials/" + materialId)
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "knowledgeId", String.valueOf(knowledgeId),
                    "title", "冲突标题",
                    "sourceLabel", "notes.pdf#p3",
                    "content", "冲突正文",
                    "expectedVersion", 1
                ))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // Unique (material_id, version) constraint at DB level rejects duplicate version
        try {
            db.update("""
                INSERT INTO rs_material_version(material_id, version, subject_id, knowledge_id, title, source_label, content, publication_status)
                VALUES (?, 2, 1, ?, 'dup', 'dup', 'dup', 'DRAFT')
                """, materialId, knowledgeId);
            throw new AssertionError("Expected DataIntegrityViolationException");
        } catch (DataIntegrityViolationException expected) {
            // Expected unique constraint violation
        }
    }

    @Test
    void publishRejectsDisabledKnowledgePoint() throws Exception {
        long materialId = json.readTree(
            mvc.perform(post("/api/admin/materials")
                    .cookie(adminCookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of(
                        "knowledgeId", String.valueOf(knowledgeId),
                        "title", "待禁用知识点资料",
                        "sourceLabel", "kp.pdf#p1",
                        "content", "正文内容"
                    ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
        ).get("materialId").asLong();

        db.update("UPDATE rs_knowledge_point SET enabled = false WHERE id = ?", knowledgeId);

        mvc.perform(post("/api/admin/materials/" + materialId + "/publish")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 1))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_BINDING"));
    }

    @Test
    void withdrawReachesPublishedVersionEvenWhenNewerDraftExists() throws Exception {
        String body = json.writeValueAsString(Map.of(
            "knowledgeId", String.valueOf(knowledgeId),
            "title", "栈的基本操作",
            "sourceLabel", "stack.pdf#p1",
            "content", "栈是后进先出的线性表。"
        ));
        long materialId = json.readTree(
            mvc.perform(post("/api/admin/materials")
                    .cookie(adminCookie).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
        ).get("materialId").asLong();

        mvc.perform(post("/api/admin/materials/" + materialId + "/publish")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 1))))
            .andExpect(status().isOk());
        assertEquals(1, searchPort.search(knowledgeId, "后进先出").size());

        // Editing creates v2 DRAFT while v1 stays live
        mvc.perform(put("/api/admin/materials/" + materialId)
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "knowledgeId", String.valueOf(knowledgeId),
                    "title", "栈的基本操作（修订）",
                    "sourceLabel", "stack.pdf#p1",
                    "content", "栈是后进先出的线性表，只能在栈顶操作。",
                    "expectedVersion", 1
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestVersion").value(2))
            .andExpect(jsonPath("$.publicationStatus").value("DRAFT"))
            .andExpect(jsonPath("$.publishedVersion").value(1));

        assertEquals(1, searchPort.search(knowledgeId, "后进先出").size(), "v1 must stay searchable while v2 is draft");

        // Withdraw is addressed by the latest version number but must hit the live v1
        mvc.perform(post("/api/admin/materials/" + materialId + "/withdraw")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "资料过时下架", "expectedVersion", 2))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.withdrawReason").value("资料过时下架"))
            .andExpect(jsonPath("$.publishedVersion").value(org.hamcrest.Matchers.nullValue()));

        assertTrue(searchPort.search(knowledgeId, "后进先出").isEmpty(), "Withdrawn material must stop matching immediately");

        Map<String, Object> v1Row = db.queryForMap(
            "SELECT publication_status, withdraw_reason, withdrawn_by FROM rs_material_version WHERE material_id=? AND version=1",
            materialId
        );
        assertEquals("WITHDRAWN", v1Row.get("publication_status"));
        assertEquals("资料过时下架", v1Row.get("withdraw_reason"));
        assertEquals(adminUserId, ((Number) v1Row.get("withdrawn_by")).longValue());

        // Reason survives a fresh read, not only the withdraw response
        mvc.perform(get("/api/admin/materials/" + materialId).cookie(adminCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.withdrawReason").value("资料过时下架"));

        // Publishing a new version brings the material back
        mvc.perform(put("/api/admin/materials/" + materialId)
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "knowledgeId", String.valueOf(knowledgeId),
                    "title", "栈的基本操作（二次修订）",
                    "sourceLabel", "stack.pdf#p1",
                    "content", "栈是后进先出的线性表，更新版。",
                    "expectedVersion", 2
                ))))
            .andExpect(status().isOk());
        mvc.perform(post("/api/admin/materials/" + materialId + "/publish")
                .cookie(adminCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("expectedVersion", 3))))
            .andExpect(status().isOk());
        assertEquals(1, searchPort.search(knowledgeId, "后进先出").size());
    }

    @Test
    void studentAndAnonymousAccessAreDenied() throws Exception {
        List<String> getEndpoints = List.of(
            "/api/admin/materials",
            "/api/admin/materials/1",
            "/api/admin/materials/search-check?query=test"
        );
        for (String endpoint : getEndpoints) {
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get(endpoint).cookie(studentCookie)).andExpect(status().isForbidden());
        }

        mvc.perform(post("/api/admin/materials")
                .cookie(studentCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());

        mvc.perform(put("/api/admin/materials/1")
                .cookie(studentCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/materials/1/publish")
                .cookie(studentCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/materials/1/withdraw")
                .cookie(studentCookie).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }
}
