package com.pivotos.ai.coding.controller;

import com.pivotos.ai.coding.api.dto.CodingRequest;
import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.ai.coding.service.CodingService;
import com.pivotos.common.core.result.R;
import org.springframework.web.bind.annotation.*;

/**
 * AI Coding REST controller.
 * <p>
 * POST /ai-coding/parse - Parse natural language and generate code
 * GET  /ai-coding/session/{id} - View session detail
 * POST /ai-coding/session/{id}/apply - Apply generated code to project
 *
 * @author PivotOS
 * @since 2.2.0
 */
@RestController
@RequestMapping("/ai-coding")
public class CodingController {

    private final CodingService codingService;

    public CodingController(CodingService codingService) {
        this.codingService = codingService;
    }

    /**
     * Parse natural language description and generate code preview.
     */
    @PostMapping("/parse")
    public R<CodingSessionVO> parse(@RequestBody CodingRequest request) {
        return R.ok(codingService.parseAndGenerate(request.getDescription()));
    }

    /**
     * Get session detail with generated file list.
     */
    @GetMapping("/session/{id}")
    public R<CodingSessionVO> session(@PathVariable Long id) {
        return R.ok(codingService.getSession(id));
    }

    /**
     * Apply generated code files to project directories.
     */
    @PostMapping("/session/{id}/apply")
    public R<Void> apply(@PathVariable Long id) {
        codingService.applyToProject(id);
        return R.ok();
    }
}
