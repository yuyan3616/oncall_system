package org.example.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.rag.config.RagSearchProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class RetrievalPostProcessor {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[^\\p{L}\\p{N}\\u4e00-\\u9fa5]+");
    private final RagSearchProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RetrievalPostProcessor(RagSearchProperties properties) {
        this.properties = properties;
    }

    public List<RetrievalCandidate> process(String question, List<RetrievalCandidate> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int safeTopK = Math.max(1, topK);

        List<RetrievalCandidate> working = new ArrayList<>(candidates);
        if (properties.getPostprocess().isDedupEnabled()) {
            working = deduplicate(working);
        }

        for (RetrievalCandidate candidate : working) {
            candidate.setVectorSimilarity(vectorSimilarity(candidate.getRawScore()));
            if (properties.getPostprocess().isRerankEnabled()) {
                candidate.setFinalScore(score(question, candidate));
            } else {
                candidate.setFinalScore(candidate.getVectorSimilarity());
            }
        }

        working.sort(Comparator
                .comparingDouble(RetrievalCandidate::getFinalScore).reversed()
                .thenComparingDouble(RetrievalCandidate::getVectorSimilarity).reversed());

        if (working.size() <= safeTopK) {
            return working;
        }
        return new ArrayList<>(working.subList(0, safeTopK));
    }

    List<RetrievalCandidate> deduplicate(List<RetrievalCandidate> candidates) {
        Map<String, RetrievalCandidate> dedupMap = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : candidates) {
            String key = dedupKey(candidate);
            RetrievalCandidate existing = dedupMap.get(key);
            if (existing == null) {
                dedupMap.put(key, candidate);
                continue;
            }
            if (vectorSimilarity(candidate.getRawScore()) > vectorSimilarity(existing.getRawScore())) {
                dedupMap.put(key, candidate);
            }
        }
        return new ArrayList<>(dedupMap.values());
    }

    private String dedupKey(RetrievalCandidate candidate) {
        if (candidate.getId() != null && !candidate.getId().isBlank()) {
            return "id:" + candidate.getId();
        }
        return "hash:" + sha256(candidate.getContent() == null ? "" : candidate.getContent());
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private double score(String question, RetrievalCandidate candidate) {
        RagSearchProperties.Postprocess postprocess = properties.getPostprocess();
        double vector = candidate.getVectorSimilarity();
        double keyword = keywordOverlap(question, candidate);
        double titleMetadata = titleMetadataBoost(question, candidate);
        return postprocess.getVectorWeight() * vector
                + postprocess.getKeywordWeight() * keyword
                + postprocess.getTitleMetadataWeight() * titleMetadata;
    }

    double vectorSimilarity(float rawScore) {
        if (rawScore < 0) {
            return 1.0D;
        }
        return 1.0D / (1.0D + rawScore);
    }

    double keywordOverlap(String question, RetrievalCandidate candidate) {
        Set<String> queryTokens = tokenize(question);
        if (queryTokens.isEmpty()) {
            return 0D;
        }
        Set<String> contentTokens = tokenize(candidate.getContent() == null ? "" : candidate.getContent());
        if (contentTokens.isEmpty()) {
            return 0D;
        }
        long matches = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) matches / (double) queryTokens.size();
    }

    double titleMetadataBoost(String question, RetrievalCandidate candidate) {
        Set<String> queryTokens = tokenize(question);
        if (queryTokens.isEmpty()) {
            return 0D;
        }

        String title = extractTitle(candidate.getMetadata());
        String metadata = candidate.getMetadata() == null ? "" : candidate.getMetadata();

        boolean titleHit = containsAnyToken(title, queryTokens);
        boolean metadataHit = containsAnyToken(metadata, queryTokens);
        int hits = (titleHit ? 1 : 0) + (metadataHit ? 1 : 0);
        return hits / 2.0D;
    }

    private boolean containsAnyToken(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return false;
        }
        String normalizedText = text.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (token.length() < 2) {
                continue;
            }
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String extractTitle(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            JsonNode titleNode = node.get("title");
            if (titleNode != null && !titleNode.isNull()) {
                return titleNode.asText("");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] rawTokens = TOKEN_PATTERN.split(text.toLowerCase(Locale.ROOT));
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : rawTokens) {
            String token = raw.trim();
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        if (!tokens.isEmpty()) {
            return tokens;
        }
        return Set.of(text.trim().toLowerCase(Locale.ROOT));
    }
}
