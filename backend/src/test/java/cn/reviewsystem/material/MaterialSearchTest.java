package cn.reviewsystem.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.reviewsystem.material.MaterialDtos.MaterialDetail;
import cn.reviewsystem.material.MaterialDtos.MaterialWriteRequest;
import cn.reviewsystem.material.MaterialDtos.PublishRequest;
import cn.reviewsystem.material.MaterialDtos.WithdrawRequest;
import cn.reviewsystem.material.MaterialSearchPort.MaterialChunkHit;
import cn.reviewsystem.security.IdentityService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MaterialSearchTest {

    @Autowired MaterialService materialService;
    @Autowired MaterialSearchPort searchPort;
    @Autowired JdbcTemplate db;
    @Autowired IdentityService identity;

    private long kpA;
    private long kpB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String adminName = "m08_search_" + suffix;
        identity.register(adminName, "Password12345!");
        db.update("UPDATE rs_user SET role='ADMIN' WHERE username=?", adminName);
        var admin = identity.find(adminName);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        long chapterId = db.queryForObject(
            "INSERT INTO rs_chapter(subject_id, code, name, sort_order) VALUES (1, ?, ?, 98) RETURNING id",
            Long.class,
            "S8" + suffix.substring(0, 6),
            "Search Chapter " + suffix
        );
        kpA = db.queryForObject(
            "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order) VALUES (1, ?, ?, ?, 1) RETURNING id",
            Long.class, chapterId, "SA-" + suffix.substring(0, 6), "Search KP A " + suffix
        );
        kpB = db.queryForObject(
            "INSERT INTO rs_knowledge_point(subject_id, chapter_id, code, name, sort_order) VALUES (1, ?, ?, ?, 2) RETURNING id",
            Long.class, chapterId, "SB-" + suffix.substring(0, 6), "Search KP B " + suffix
        );
    }

    @Test
    void draftNotSearchablePublishedSearchableAndWithdrawnImmediatelyExcluded() {
        MaterialDetail draft = materialService.create(new MaterialWriteRequest(
            String.valueOf(kpA),
            "哈夫曼编码指南",
            "huffman.pdf#p1",
            "哈夫曼树是最优二叉树，用于数据压缩编码。",
            null
        ));
        long matId = Long.parseLong(draft.materialId());

        // Before publish -> empty
        assertTrue(searchPort.search(kpA, "哈夫曼").isEmpty());

        // Publish -> hits
        materialService.publish(matId, new PublishRequest(1));
        List<MaterialChunkHit> hits = searchPort.search(kpA, "哈夫曼");
        assertEquals(1, hits.size());
        assertEquals("哈夫曼编码指南", hits.get(0).title());

        // Other knowledge point -> empty
        assertTrue(searchPort.search(kpB, "哈夫曼").isEmpty());

        // Withdraw -> immediately empty (AC-25)
        materialService.withdraw(matId, new WithdrawRequest("撤回测试", 1));
        assertTrue(searchPort.search(kpA, "哈夫曼").isEmpty());
    }

    @Test
    void rankingByHitCountDescThenChunkIdAscAndCappedAtThree() {
        // Publish 4 chunks under kpA
        MaterialDetail d1 = materialService.create(new MaterialWriteRequest(
            String.valueOf(kpA),
            "排序测试资料",
            "rank.pdf#p1",
            "只包含二叉树。\n\n包含二叉树和遍历。\n\n包含二叉树、遍历和DFS搜索。\n\n也包含二叉树和遍历。",
            null
        ));
        MaterialDetail pub = materialService.publish(Long.parseLong(d1.materialId()), new PublishRequest(1));
        assertEquals(4, pub.chunks().size());

        // Query tokens: "二叉树" (bigrams: 二叉, 叉树), "遍历", "dfs" (lowercase)
        List<MaterialChunkHit> hits = searchPort.search(kpA, "二叉树 遍历 DFS");
        assertEquals(3, hits.size(), "Search results must be capped at 3");

        // Highest hit count chunk (chunk 2: 二叉, 叉树, 遍历, dfs = 4 tokens) comes first
        assertTrue(hits.get(0).text().contains("DFS"));
        assertEquals(4, hits.get(0).hitCount());

        // Next two are tie (hitCount=3: 二叉, 叉树, 遍历), ordered by chunkId ascending
        assertEquals(3, hits.get(1).hitCount());
        assertEquals(3, hits.get(2).hitCount());
        assertTrue(hits.get(1).chunkId() < hits.get(2).chunkId());
    }

    @Test
    void emptyQueryFiltersByKnowledgePointOnlyAndTokenizationIsCaseInsensitive() {
        MaterialDetail mA = materialService.create(new MaterialWriteRequest(
            String.valueOf(kpA), "AVL树", "avl.pdf#p1", "AVL平衡因子定义", null
        ));
        materialService.publish(Long.parseLong(mA.materialId()), new PublishRequest(1));

        MaterialDetail mB = materialService.create(new MaterialWriteRequest(
            String.valueOf(kpB), "KMP算法", "kmp.pdf#p1", "KMP前缀函数", null
        ));
        materialService.publish(Long.parseLong(mB.materialId()), new PublishRequest(1));

        List<MaterialChunkHit> emptyQueryHits = searchPort.search(kpA, "   ");
        assertEquals(1, emptyQueryHits.size());
        assertEquals("AVL树", emptyQueryHits.get(0).title());

        List<MaterialChunkHit> caseInsensitive = searchPort.search(kpA, "avl");
        assertEquals(1, caseInsensitive.size());
    }

    @Test
    void singleCharacterChineseQueryStillMatches() {
        MaterialDetail material = materialService.create(new MaterialWriteRequest(
            String.valueOf(kpA),
            "栈与队列",
            "stack-queue.pdf#p1",
            "栈是后进先出的线性表。\n\n队列是先进先出的线性表。",
            null
        ));
        materialService.publish(Long.parseLong(material.materialId()), new PublishRequest(1));

        assertEquals(1, searchPort.search(kpA, "栈").size(), "Single CJK character must not be dropped by tokenizer");
        assertEquals(1, searchPort.search(kpA, "队列").size());
    }
}
