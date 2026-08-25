// VERIFY-007 LeviLamina i18n error-code diff regression test.
//
// Asserts the LeviLamina i18n bundles (en_US + zh_CN) contain the canonical
// NC-* error codes. The audit lists 26 codes:
//   NC-400, 401, 403, 404, 409, 410, 411, 420, 429,
//   430, 431, 432, 433, 434, 435, 436, 437, 438, 439,
//   500, 501, 502, 503, 504, 510, 511
//
// For each code and each locale, both the "<error>.NC-XXX.message" and the
// "error.NC-XXX.suggestion" keys must resolve to a non-empty localized value
// (NOT the key itself — the I18n fallback chain returns the key when the
// bundle is missing, so we assert the returned value differs from the key to
// catch a missing-code regression where the bundle exists but the code was
// never added). Also asserts errorMessage() produces a non-empty composite
// string containing the localized message.
//
// No production source is touched. The test loads the I18n singleton the same
// way test_protocol.cpp does; the xmake after_build hook copies src/i18n/lang
// next to the binary so the loader finds the bundles.
//
// Build & run:
//   xmake f --sdk=n -m debug
//   xmake build novachat-levilamina-i18n-parity-tests
//   xmake run novachat-levilamina-i18n-parity-tests
//
// Exits 0 on success, non-zero on the first failure.

#include "../src/i18n/I18n.h"

#include <cstdio>
#include <string>
#include <vector>

using namespace novachat::i18n;

// ---------------------------------------------------------------------------
// Test framework (same idiom as test_protocol.cpp)
// ---------------------------------------------------------------------------
static int gPassed = 0;
static int gFailed = 0;

#define CHECK(cond) do { \
    if (cond) { ++gPassed; } \
    else { ++gFailed; std::printf("FAIL: %s (%s:%d)\n", #cond, __FILE__, __LINE__); } \
} while (0)

// ---------------------------------------------------------------------------
// The 26 canonical NC error codes from the VERIFY-007 audit slice.
// ---------------------------------------------------------------------------
static const std::vector<std::string> kCanonicalCodes = {
    "NC-400", "NC-401", "NC-403", "NC-404", "NC-409", "NC-410", "NC-411",
    "NC-420", "NC-429",
    "NC-430", "NC-431", "NC-432", "NC-433", "NC-434", "NC-435", "NC-436",
    "NC-437", "NC-438", "NC-439",
    "NC-500", "NC-501", "NC-502", "NC-503", "NC-504", "NC-510", "NC-511",
};

static const std::vector<std::string> kLocales = {"en_US", "zh_CN"};

// ---------------------------------------------------------------------------
// VERIFY-007: for every canonical code and locale, assert both the message
// and suggestion keys resolve to a non-empty value that is NOT the key itself
// (the I18n fallback returns the key when missing, so "value == key" means the
// code was never localized). Also assert errorMessage() composites a non-empty
// string that contains the localized message.
// ---------------------------------------------------------------------------
static void testAllCanonicalCodesPresent() {
    std::printf("testAllCanonicalCodesPresent (%d codes x %d locales)...\n",
                static_cast<int>(kCanonicalCodes.size()),
                static_cast<int>(kLocales.size()));
    auto& i18n = I18n::getInstance();

    for (const auto& code : kCanonicalCodes) {
        for (const auto& locale : kLocales) {
            const std::string msgKey = "error." + code + ".message";
            const std::string sugKey = "error." + code + ".suggestion";

            const std::string msg = i18n.get(msgKey, locale);
            const std::string sug = i18n.get(sugKey, locale);

            if (!(!msg.empty())) {
                ++gFailed; std::printf("FAIL: %s.%s empty (%s:%d)\n",
                    code.c_str(), locale.c_str(), __FILE__, __LINE__);
            } else ++gPassed;

            if (!(msg != msgKey)) {
                ++gFailed; std::printf("FAIL: %s.%s message == key (missing code) (%s:%d)\n",
                    code.c_str(), locale.c_str(), __FILE__, __LINE__);
            } else ++gPassed;

            if (!(!sug.empty())) {
                ++gFailed; std::printf("FAIL: %s.%s suggestion empty (%s:%d)\n",
                    code.c_str(), locale.c_str(), __FILE__, __LINE__);
            } else ++gPassed;

            if (!(sug != sugKey)) {
                ++gFailed; std::printf("FAIL: %s.%s suggestion == key (missing code) (%s:%d)\n",
                    code.c_str(), locale.c_str(), __FILE__, __LINE__);
            } else ++gPassed;

            // errorMessage() composites "§c<message> §7<prefix> <suggestion>".
            const std::string composite = i18n.errorMessage(code, locale);
            if (!(composite.find(msg) != std::string::npos)) {
                ++gFailed; std::printf("FAIL: errorMessage(%s,%s) missing message fragment (%s:%d)\n",
                    code.c_str(), locale.c_str(), __FILE__, __LINE__);
            } else ++gPassed;

            if (!(composite.find(sug) != std::string::npos)) {
                ++gFailed; std::printf("FAIL: errorMessage(%s,%s) missing suggestion fragment (%s:%d)\n",
                    code.c_str(), locale.c_str(), __FILE__, __LINE__);
            } else ++gPassed;
        }
    }
}

// ---------------------------------------------------------------------------
// Sanity: the suggestion_prefix key must exist (errorMessage relies on it).
// ---------------------------------------------------------------------------
static void testSuggestionPrefixPresent() {
    std::printf("testSuggestionPrefixPresent...\n");
    auto& i18n = I18n::getInstance();
    for (const auto& locale : kLocales) {
        const std::string prefix = i18n.get("error.suggestion_prefix", locale);
        CHECK(!prefix.empty());
        CHECK(prefix != std::string("error.suggestion_prefix"));
    }
}

// ---------------------------------------------------------------------------
// Sanity: a deliberately unknown code falls back to the key itself (documents
// the fallback contract so the "value != key" assertion above is meaningful).
// ---------------------------------------------------------------------------
static void testUnknownCodeFallsBackToKey() {
    std::printf("testUnknownCodeFallsBackToKey...\n");
    auto& i18n = I18n::getInstance();
    const std::string code = "NC-999";
    const std::string msgKey = "error." + code + ".message";
    const std::string msg = i18n.get(msgKey, "en_US");
    CHECK(msg == msgKey); // fallback chain returns the key when missing
}

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);

    testSuggestionPrefixPresent();
    testAllCanonicalCodesPresent();
    testUnknownCodeFallsBackToKey();

    std::printf("\n%d passed, %d failed\n", gPassed, gFailed);
    return gFailed == 0 ? 0 : 1;
}
