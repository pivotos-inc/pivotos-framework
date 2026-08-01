package com.pivotos.ai.coding.service;

import com.pivotos.ai.coding.api.dto.CodingSessionVO;

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
