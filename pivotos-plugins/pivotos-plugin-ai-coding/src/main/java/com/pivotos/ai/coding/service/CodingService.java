package com.pivotos.ai.coding.service;

import com.pivotos.ai.coding.api.dto.CodingSessionVO;
import com.pivotos.common.core.page.PageResult;

/**
 * AI Coding service interface.
 *
 * @author PivotOS
 * @since 2.2.0
 */
public interface CodingService {

    /**
     * Parse natural language and generate code.
     *
     * @param description business description
     * @return coding session with generated files
     */
    CodingSessionVO parseAndGenerate(String description);

    /**
     * Page query coding sessions (without generated files, lightweight for list view).
     *
     * @param pageNum  page number
     * @param pageSize page size
     * @return session page
     */
    PageResult<CodingSessionVO> pageSessions(Integer pageNum, Integer pageSize);

    /**
     * Apply generated code to project (write files to disk).
     *
     * @param sessionId coding session ID
     */
    void applyToProject(Long sessionId);

    /**
     * Get session detail with generated files.
     *
     * @param sessionId session ID
     * @return session VO
     */
    CodingSessionVO getSession(Long sessionId);
}
