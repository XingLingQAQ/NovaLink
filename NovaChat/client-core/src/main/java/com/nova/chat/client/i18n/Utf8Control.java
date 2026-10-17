package com.nova.chat.client.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * {@link ResourceBundle.Control} that reads {@code .properties} files as
 * UTF-8 regardless of the platform default charset.
 *
 * <p>Java 9+ already loads {@code PropertyResourceBundle} built from an
 * {@code InputStreamReader} as UTF-8, but the default
 * {@code ResourceBundle.Control} still uses {@code ISO-8859-1} when it falls
 * back to {@code new PropertyResourceBundle(InputStream)} on some code paths.
 * This control forces an explicit UTF-8 {@code InputStreamReader} so Chinese
 * (and any other non-Latin-1) text in the .properties files is decoded
 * correctly everywhere.
 *
 * <p>Only {@code .properties} formats are handled (returns
 * {@code "java.properties"} from {@link #getFormats(String)}); class-based
 * bundles are not used.
 */
final class Utf8Control extends ResourceBundle.Control {

    /**
     * Disables {@link ResourceBundle}'s implicit default-locale fallback.
     *
     * <p>The default implementation returns {@link Locale#getDefault()} when the
     * requested locale is not the JVM default. That makes a locale with no
     * bundle of its own (e.g. {@code fr_FR}) silently resolve to whatever the
     * <em>host</em> locale is — on a developer machine with
     * {@code user.language=zh} that is the zh_CN bundle, but on an English CI
     * runner it is {@code en_US}. The same lookup then returns different text
     * per machine, which is exactly what a translation service must never do.
     *
     * <p>{@link I18n} owns the fallback chain explicitly (requested locale →
     * zh_CN → key echo), so the implicit one is redundant as well as
     * host-dependent. Returning {@code null} here restricts bundle resolution
     * to the requested locale's own chain.
     *
     * @return always {@code null} — no implicit fallback locale
     */
    @Override
    public Locale getFallbackLocale(String baseName, Locale locale) {
        return null;
    }

    @Override
    public ResourceBundle newBundle(String baseName, Locale locale, String format,
                                     ClassLoader loader, boolean reload)
            throws IOException {
        if (!"java.properties".equals(format)) {
            return null;
        }
        String bundleName = toBundleName(baseName, locale);
        String resourceName = toResourceName(bundleName, "properties");
        if (resourceName == null) {
            return null;
        }
        try (InputStream stream = openResource(loader, resourceName, reload)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return new PropertyResourceBundle(reader);
            }
        }
    }

    private InputStream openResource(ClassLoader loader, String resourceName, boolean reload)
            throws IOException {
        if (reload) {
            URL url = loader.getResource(resourceName);
            if (url == null) {
                return null;
            }
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        }
        return loader.getResourceAsStream(resourceName);
    }
}
