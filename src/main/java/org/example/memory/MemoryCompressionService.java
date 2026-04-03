package org.example.memory;

import java.util.List;
import java.util.Map;

public interface MemoryCompressionService {

    String compress(String existingSummary, List<Map<String, String>> messageBatch);
}

