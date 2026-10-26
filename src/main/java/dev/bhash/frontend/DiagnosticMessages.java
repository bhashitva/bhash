// SPDX-License-Identifier: Apache-2.0
package dev.bhash.frontend;

import java.util.regex.Pattern;

/** Stable public diagnostic categories, shared by CLI, LSP and compilation service.
 * English detail is retained for precise types/names and searchable tool output. */
public final class DiagnosticMessages {
    private DiagnosticMessages() {}
    private record Entry(String code, String hindi) {}
    private static Entry entry(String message) {
        if (message.matches("(?s).*(mismatched input|extraneous input|no viable alternative|token recognition error|missing .* at).*"))
            return new Entry("BH1001", "वाक्य रचना जाँचें; कोष्ठक और अर्धविराम सही स्थान पर रखें।");
        if (message.startsWith("Undefined variable:")) return new Entry("BH2001", "यह नाम घोषित नहीं है। नाम की वर्तनी और उसका कार्यक्षेत्र जाँचें।");
        if (message.startsWith("Unknown type:") || message.startsWith("Unknown class:")) return new Entry("BH2002", "यह प्रकार नहीं मिला। घोषणा या आयात जाँचें।");
        if (message.startsWith("Unknown import:")) return new Entry("BH2003", "यह आयात नहीं मिला। संकुल, सार्वजनिक घोषणा और परियोजना की स्रोत फ़ाइलें जाँचें।");
        if (message.startsWith("Undefined")) return new Entry("BH2004", "यह सदस्य या कार्य नहीं मिला। नाम और प्राप्तकर्ता का प्रकार जाँचें।");
        if (message.startsWith("Expected ") && message.contains(", found ")) return new Entry("BH3001", "मान का प्रकार अपेक्षित प्रकार से अलग है। उपयुक्त मान या स्पष्ट परिवर्तन का उपयोग करें।");
        if (message.startsWith("Cannot assign to an immutable")) return new Entry("BH4001", "स्थिर बंधन या क्षेत्र को दोबारा मान नहीं दे सकते। परिवर्तन चाहिए तो मान का उपयोग करें।");
        if (message.startsWith("Function must return") || message.startsWith("Return value required") || message.startsWith("Void function"))
            return new Entry("BH4002", "कार्य के लौटाए जाने वाले मान और सभी वापसी पथ जाँचें।");
        if (message.startsWith("Unreachable") || message.startsWith("Loop control") || message.startsWith("Cannot return from") || message.startsWith("Cannot leave"))
            return new Entry("BH4003", "नियंत्रण प्रवाह जाँचें। यह कथन इस स्थान पर मान्य नहीं है।");
        if (message.contains("not accessible") || message.contains("not public outside")) return new Entry("BH5001", "इस घोषणा तक यहाँ से पहुँच की अनुमति नहीं है। दृश्यता जाँचें।");
        if (message.startsWith("Duplicate") || message.startsWith("Import conflicts") || message.startsWith("Reserved"))
            return new Entry("BH6001", "नाम या हस्ताक्षर पहले से उपयोग में है। घोषणा या उपनाम बदलें।");
        if (message.startsWith("Wrong argument count") || message.startsWith("Expected matching") || message.startsWith("Ambiguous"))
            return new Entry("BH7001", "कार्य के तर्कों की संख्या और प्रकार जाँचें; एक स्पष्ट अधिभार चुनना आवश्यक है।");
        return new Entry("BH9001", "भाष के इस नियम का पालन नहीं हुआ। नीचे दिया गया विवरण जाँचें।");
    }
    public static String code(String message) {
        var match = Pattern.compile("^\\[(BH[0-9]{4})\\]").matcher(message);
        return match.find() ? match.group(1) : entry(message).code;
    }
    public static String explain(String message) {
        if (message.startsWith("[BH")) return message;
        var e=entry(message);
        return "["+e.code+"] "+e.hindi+" — "+message;
    }
    public static String render(String message) {
        // A greedy source capture also handles drive letters and colons in paths.
        var location=Pattern.compile("^(.*:[0-9]+:[0-9]+: )(.*)$");
        return String.join("\n", message.lines().map(line -> {
            var match=location.matcher(line);
            return match.matches() ? match.group(1)+explain(match.group(2)) : line;
        }).toList());
    }
}
