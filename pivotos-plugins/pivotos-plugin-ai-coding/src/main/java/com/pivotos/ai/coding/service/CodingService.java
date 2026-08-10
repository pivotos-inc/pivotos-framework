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
     * Page query coding sessions owned by the user (without generated files, lightweight for list view).
     *
     * @param userId   current login user ID (row-level isolation by create_by)
     * @param pageNum  page number
     * @param pageSize page size
     * @return session page
     */
    PageResult<CodingSessionVO> pageSessions(Long userId, Integer pageNum, Integer pageSize);

    /**
     * Apply generated code to project (write files to disk). Only the session owner may apply.
     *
     * @param userId    current login user ID
     * @param sessionId coding session ID
     */
    void applyToProject(Long userId, Long sessionId);

    /**
     * Get session detail with generated files. Only the session owner may view.
     *
     * @param userId    current login user ID
     * @param sessionId session ID
     * @return session VO
     */
    CodingSessionVO getSession(Long userId, Long sessionId);
}
