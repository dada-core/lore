package com.dadacore.lore.ingestion.worker;

/**
 * A file whose text cannot be extracted, whatever the number of attempts.
 *
 * <p>Codes: {@code encrypted} (protected by a password), {@code corrupt} (damaged, or built to
 * exhaust the extraction), {@code unsupported_content} (not of the format its name announces).
 */
final class ExtractionFailure extends Exception {

    static final String ENCRYPTED = "encrypted";
    static final String CORRUPT = "corrupt";
    static final String UNSUPPORTED_CONTENT = "unsupported_content";

    private final String code;

    ExtractionFailure(String code, String message) {
        this(code, message, null);
    }

    ExtractionFailure(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    String code() {
        return code;
    }
}
