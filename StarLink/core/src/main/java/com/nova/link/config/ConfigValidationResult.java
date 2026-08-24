package com.nova.link.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of validating a candidate YAML configuration via
 * {@link ConfigLoader#validateYaml(String)}.
 *
 * <p>§11.6 Project 20 (proposal 10): the Panel exposes
 * {@code POST /api/settings/validate} so an operator can dry-run a full YAML
 * document against the same structural rules the loader enforces on real
 * load/save, without persisting anything. The handler wraps the result into a
 * JSON object; this POJO keeps the handler code free of ad-hoc list assembly.
 *
 * <p>{@code valid} is {@code true} iff {@code errors} is empty. {@code warnings}
 * is reserved for non-blocking notices (currently always empty — the loader
 * does not emit warnings, but the field is part of the contract so the frontend
 * can render them without a schema change later). Each {@link ValidationError}
 * carries a {@code path} (which may be {@code null} when the underlying
 * exception did not carry one) and a human-readable {@code message} that
 * already includes any path线索 the loader embedded.
 *
 * <p>Immutable: all collections are defensively copied on construction and
 * exposed as unmodifiable views.
 */
public final class ConfigValidationResult {

    private final boolean valid;
    private final List<ValidationError> errors;
    private final List<String> warnings;

    public ConfigValidationResult(boolean valid,
                                  List<ValidationError> errors,
                                  List<String> warnings) {
        this.valid = valid;
        this.errors = errors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(errors));
        this.warnings = warnings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    /**
     * Convenience factory for a successful validation (no errors, no warnings).
     */
    public static ConfigValidationResult ok() {
        return new ConfigValidationResult(true, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Convenience factory for a failed validation with a single error and no
     * warnings. The error's {@code path} may be {@code null}.
     */
    public static ConfigValidationResult failure(String path, String message) {
        return new ConfigValidationResult(false,
                List.of(new ValidationError(path, message)),
                Collections.emptyList());
    }

    public boolean isValid() {
        return valid;
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigValidationResult that = (ConfigValidationResult) o;
        return valid == that.valid
                && Objects.equals(errors, that.errors)
                && Objects.equals(warnings, that.warnings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valid, errors, warnings);
    }

    @Override
    public String toString() {
        return "ConfigValidationResult{valid=" + valid
                + ", errors=" + errors
                + ", warnings=" + warnings + "}";
    }

    /**
     * A single validation issue. {@code path} is {@code null} when the
     * underlying parse error did not identify a specific config path (the
     * loader's exceptions embed path线索 in {@code message} already, so the
     * handler does not synthesise a fake path).
     */
    public static final class ValidationError {

        private final String path;
        private final String message;

        public ValidationError(String path, String message) {
            this.path = path;
            this.message = message;
        }

        public String getPath() {
            return path;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ValidationError that = (ValidationError) o;
            return Objects.equals(path, that.path)
                    && Objects.equals(message, that.message);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, message);
        }

        @Override
        public String toString() {
            return "ValidationError{path=" + path + ", message=" + message + "}";
        }
    }
}
