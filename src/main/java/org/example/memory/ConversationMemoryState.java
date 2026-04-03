package org.example.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemoryState {

    private String sessionId;

    private String summary;

    @Builder.Default
    private List<Map<String, String>> recentMessages = new ArrayList<>();

    private int totalMessagePairs;

    private int compressedMessagePairs;

    private int pendingCompressionPairs;
}

