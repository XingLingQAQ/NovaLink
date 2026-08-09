package com.nova.link.i18n;

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
 * UTF-8 regardless of the platform default charset. Backend copy of the
 * client-core Utf8Control (kept independent so novalink-core has no
 * client-core dependency).
 */
final class Utf8Control extends ResourceBundle.Control {

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
