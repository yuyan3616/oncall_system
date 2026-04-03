package org.example.rag.retrieval;

import lombok.Builder;
import lombok.Value;
import org.example.rag.rewrite.RewriteResult;

import java.util.List;

@Value
@Builder
public class MultiChannelRetrievalResult {

    String originalQuestion;
    RewriteResult rewriteResult;
    List<RetrievalCandidate> mergedCandidates;
    List<RetrievalCandidate> finalCandidates;
}
