package cn.reviewsystem.material;

import cn.reviewsystem.api.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Deterministic splitter for course material content into indexed chunks (1..1000 chars each).
 */
@Component
public class MaterialChunkSplitter {

    public static final int MAX_CHUNK_LENGTH = 1000;
    private static final Pattern BLANK_LINE_SPLIT = Pattern.compile("\\n\\s*\\n+");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。；;！？!?.\\n])");

    public List<Chunk> split(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Material content must not be blank");
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n').trim();
        String[] paragraphs = BLANK_LINE_SPLIT.split(normalized);

        List<String> pieces = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= MAX_CHUNK_LENGTH) {
                pieces.add(trimmed);
            } else {
                splitLongParagraph(trimmed, pieces);
            }
        }

        if (pieces.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Material content must produce at least one chunk");
        }

        List<Chunk> result = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            result.add(new Chunk(i, pieces.get(i)));
        }
        return result;
    }

    private void splitLongParagraph(String paragraph, List<String> out) {
        String[] sentences = SENTENCE_BOUNDARY.split(paragraph);
        StringBuilder current = new StringBuilder();
        for (String raw : sentences) {
            String sentence = raw.trim();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > MAX_CHUNK_LENGTH) {
                if (!current.isEmpty()) {
                    out.add(current.toString().trim());
                    current.setLength(0);
                }
                hardSplit(sentence, out);
                continue;
            }
            if (current.isEmpty()) {
                current.append(sentence);
            } else if (current.length() + 1 + sentence.length() <= MAX_CHUNK_LENGTH) {
                current.append(' ').append(sentence);
            } else {
                out.add(current.toString().trim());
                current.setLength(0);
                current.append(sentence);
            }
        }
        if (!current.isEmpty()) {
            String tail = current.toString().trim();
            if (!tail.isEmpty()) {
                out.add(tail);
            }
        }
    }

    private void hardSplit(String text, List<String> out) {
        int index = 0;
        while (index < text.length()) {
            int end = Math.min(index + MAX_CHUNK_LENGTH, text.length());
            String slice = text.substring(index, end).trim();
            if (!slice.isEmpty()) {
                out.add(slice);
            }
            index = end;
        }
    }

    public record Chunk(int position, String text) {}
}
