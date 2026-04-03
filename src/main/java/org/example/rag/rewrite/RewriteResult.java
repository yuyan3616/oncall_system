package org.example.rag.rewrite;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class RewriteResult {
    String normalizedQuestion;
    String rewrittenQuestion;
    List<String> subQuestions;
    boolean llmApplied;
}
