package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.retrieval.MultiChannelRetrievalResult;
import org.example.rag.retrieval.MultiChannelRetrievalService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    private final MultiChannelRetrievalService retrievalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${rag.top-k:3}")
    private int topK = 3;

    @Autowired
    public InternalDocsTools(MultiChannelRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. "
            + "It performs query rewrite, multi-channel retrieval and rerank to improve relevance.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for")
            String query) {
        try {
            MultiChannelRetrievalResult retrievalResult = retrievalService.retrieve(query, topK);
            List<VectorSearchService.SearchResult> searchResults =
                    retrievalService.toSearchResults(retrievalResult.getFinalCandidates());

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            return objectMapper.writeValueAsString(searchResults);
        } catch (Exception e) {
            logger.error("[tool_error] queryInternalDocs failed", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }
}
