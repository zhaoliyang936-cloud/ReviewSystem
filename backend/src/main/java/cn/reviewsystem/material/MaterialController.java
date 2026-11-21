package cn.reviewsystem.material;

import cn.reviewsystem.api.BusinessException;
import cn.reviewsystem.material.MaterialDtos.MaterialDetail;
import cn.reviewsystem.material.MaterialDtos.MaterialListItem;
import cn.reviewsystem.material.MaterialDtos.MaterialWriteRequest;
import cn.reviewsystem.material.MaterialDtos.PublishRequest;
import cn.reviewsystem.material.MaterialDtos.WithdrawRequest;
import cn.reviewsystem.material.MaterialSearchPort.MaterialChunkHit;
import cn.reviewsystem.question.PageResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/materials")
public class MaterialController {

    private final MaterialService service;
    private final MaterialSearchPort searchPort;

    public MaterialController(MaterialService service, MaterialSearchPort searchPort) {
        this.service = service;
        this.searchPort = searchPort;
    }

    @GetMapping
    public PageResult<MaterialListItem> list(
        @RequestParam(required = false) String knowledgeId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(knowledgeId, status, page, size);
    }

    @GetMapping("/{materialId:\\d+}")
    public MaterialDetail detail(@PathVariable long materialId) {
        return service.detail(materialId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialDetail create(@RequestBody MaterialWriteRequest request) {
        return service.create(request);
    }

    @PutMapping("/{materialId:\\d+}")
    public MaterialDetail update(@PathVariable long materialId, @RequestBody MaterialWriteRequest request) {
        return service.update(materialId, request);
    }

    @PostMapping("/{materialId:\\d+}/publish")
    public MaterialDetail publish(@PathVariable long materialId, @RequestBody PublishRequest request) {
        return service.publish(materialId, request);
    }

    @PostMapping("/{materialId:\\d+}/withdraw")
    public MaterialDetail withdraw(@PathVariable long materialId, @RequestBody WithdrawRequest request) {
        return service.withdraw(materialId, request);
    }

    @GetMapping("/search-check")
    public List<MaterialChunkHit> searchCheck(
        @RequestParam(required = false) String knowledgeId,
        @RequestParam(required = false) String query
    ) {
        Long kpId = null;
        if (knowledgeId != null && !knowledgeId.isBlank()) {
            try {
                kpId = Long.parseLong(knowledgeId.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BINDING", "Invalid knowledgeId");
            }
        }
        return searchPort.search(kpId, query);
    }
}
