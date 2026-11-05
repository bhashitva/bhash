// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import org.antlr.v4.runtime.*;

/** Symbol tables must use this key, preserving original spelling for diagnostics. */
public final class Identifiers {
    private Identifiers() {}
    public static String key(String spelling) {
        return Normalizer.normalize(spelling.replace("\u200C", "").replace("\u200D", ""), Normalizer.Form.NFC);
    }

    private static final Map<String, Integer> KEYWORDS = new HashMap<>();

    public static String describe(String spelling) {
        return spelling + " [" + spelling.codePoints().mapToObj(point -> String.format("U+%04X",point))
                .collect(java.util.stream.Collectors.joining(" ")) + "]";
    }

    /** Keeps source spelling and offsets while giving equivalent reserved words the same token type. */
    public static Token token(Lexer lexer, Token token, int identifierType) {
        if (token.getType() != identifierType) return token;
        String spelling = token.getText();
        int[] points = spelling.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            int point = points[i];
            boolean joiner = point == 0x200C || point == 0x200D;
            boolean allowed = i == 0 ? IdentifierProfile.start(point) : IdentifierProfile.part(point);
            if (joiner) {
                int before = i - 2;
                if (before >= 0 && points[before] == 0x93C) before--;
                allowed = before >= 0 && points[i - 1] == 0x94D && IdentifierProfile.start(points[before])
                        && i + 1 < points.length && IdentifierProfile.start(points[i + 1]);
            }
            if (!allowed) {
                String code = joiner ? "BH1003" : i == 0 ? "BH1002" : "BH1004";
                String hindi = joiner ? "इस स्थान पर जोड़ने वाला अदृश्य चिह्न स्वीकार्य नहीं है।"
                        : i == 0 ? "नाम की शुरुआत इस अक्षर या चिह्न से नहीं हो सकती।"
                        : "यह अक्षर या चिह्न भाष की पहचानकर्ता सूची में शामिल नहीं है।";
                String name = Character.getName(point);
                String message = "[" + code + "] " + hindi + " — " + String.format("U+%04X", point)
                        + (name == null ? "" : " " + name);
                lexer.getErrorListenerDispatch().syntaxError(lexer, token, token.getLine(),
                        token.getCharPositionInLine() + i, message, null);
                return token;
            }
        }
        String canonical = key(spelling);
        synchronized (KEYWORDS) {
            if (KEYWORDS.isEmpty()) {
                Vocabulary vocabulary = lexer.getVocabulary();
                for (int i = 1; i <= vocabulary.getMaxTokenType(); i++) {
                    String literal = vocabulary.getLiteralName(i);
                    if (literal == null) continue;
                    String word = literal.substring(1, literal.length() - 1);
                    word = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(word)
                            .replaceAll(m -> Character.toString((char) Integer.parseInt(m.group(1), 16)));
                    if (!word.isEmpty() && IdentifierProfile.start(word.codePointAt(0))) KEYWORDS.put(key(word), i);
                }
            }
            Integer type = KEYWORDS.get(canonical);
            if (type != null) ((WritableToken) token).setType(type);
        }
        return token;
    }
}
