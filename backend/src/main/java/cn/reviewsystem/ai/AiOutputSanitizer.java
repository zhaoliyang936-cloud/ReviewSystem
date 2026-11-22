package cn.reviewsystem.ai;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AiOutputSanitizer {
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>");
    private static final Pattern SCRIPT_TAG = Pattern.compile("(?i)</?script\\b[^>]*>");
    private static final Pattern IFRAME_BLOCK = Pattern.compile("(?is)<iframe\\b[^>]*>.*?</iframe>");
    private static final Pattern IFRAME_TAG = Pattern.compile("(?i)</?iframe\\b[^>]*>");
    private static final Pattern EVENT_ATTR = Pattern.compile("(?i)\\bon[a-z0-9_]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_PROTO = Pattern.compile("(?i)javascript\\s*:");
    private static final Pattern MD_REMOTE_IMG = Pattern.compile("!\\s*\\[[^\\]]*\\]\\s*\\(\\s*(?:https?:|//)[^)]*\\)");
    private static final Pattern HTML_REMOTE_IMG = Pattern.compile("(?i)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']?(?:https?:|//)[^>]*>");
    private static final Pattern MD_REMOTE_LINK = Pattern.compile("\\[([^\\]]*)\\]\\s*\\(\\s*(?:https?:|//)[^)]*\\)");
    private static final Pattern RAW_URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern ANY_HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String out = raw;
        out = SCRIPT_BLOCK.matcher(out).replaceAll("");
        out = SCRIPT_TAG.matcher(out).replaceAll("");
        out = IFRAME_BLOCK.matcher(out).replaceAll("");
        out = IFRAME_TAG.matcher(out).replaceAll("");
        out = EVENT_ATTR.matcher(out).replaceAll("");
        out = JS_PROTO.matcher(out).replaceAll("");
        out = MD_REMOTE_IMG.matcher(out).replaceAll("");
        out = HTML_REMOTE_IMG.matcher(out).replaceAll("");
        out = MD_REMOTE_LINK.matcher(out).replaceAll("$1");
        out = RAW_URL.matcher(out).replaceAll("");
        out = ANY_HTML_TAG.matcher(out).replaceAll("");
        return out.trim();
    }

    public static boolean hasBlockedContent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        return SCRIPT_TAG.matcher(raw).find()
                || IFRAME_TAG.matcher(raw).find()
                || EVENT_ATTR.matcher(raw).find()
                || JS_PROTO.matcher(raw).find()
                || MD_REMOTE_IMG.matcher(raw).find()
                || HTML_REMOTE_IMG.matcher(raw).find();
    }
}
