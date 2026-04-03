package org.example.rag.retrieval;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RetrievalCandidate {

    private String id;

    private String content;

    private String metadata;

    private float rawScore;

    private String channel;

    private String queryUsed;

    private double vectorSimilarity;

    private double finalScore;
}
