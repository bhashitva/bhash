package dev.bhash;

final class UnicodeIdentifierFixtures {
    static final String SOURCE = source();
    private static String source() {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of("tutorial/unicode-identifiers/identifiers.bhash"));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
    static final String OUTPUT = "५ ५ ५ ६ ७ ८ ९ ६\n३.०\n६\nक्‍ष असत्य\n";
}
