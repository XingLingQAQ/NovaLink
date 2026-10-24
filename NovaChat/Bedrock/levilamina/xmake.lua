-- NovaChat-LeviLamina Build Configuration
-- xmake build system for LeviLamina (BDS) plugin
--
-- Aligns with the official LeviLamina mod template
-- (LiteLDev/levilamina-mod-template): the LeviLamina SDK is a SOURCE package
-- (its xmake-repo package def uses add_urls(git) + on_install(xmake.install),
-- so it is ALWAYS compiled from source with MSVC -- there is no prebuilt
-- binary), and it ALWAYS compiles its own sources with the MD (release)
-- runtime. Two consequences we handle here:
--   1. set_runtimes("MD") forces the WHOLE toolchain -- levilamina + its deps
--      (fmt/leveldb/entt/...) + our targets -- to MD. Without this, `xmake f
--      -m debug` flips the deps to MDd while levilamina stays MD, and link
--      fails with LNK2038 (RuntimeLibrary MDd vs MD) + LNK2019/LNK2001 on
--      debug-CRT symbols (__imp__CrtDbgReport / __imp__calloc_dbg).
--   2. levibuildscript provides LeviLamina's official link rule + mod-packer
--      rule (add_rules("@levibuildscript/linkrule"|"modpacker")) -- a pure-Lua
--      rules package (no compile cost) that the template uses for correct
--      linking + manifest packing. We apply it to the plugin target.
add_rules("mode.debug", "mode.release")

-- Force MD runtime everywhere so debug mode stays runtime-consistent with
-- levilamina's MD sources. (Official template guard: only set when the user
-- has not explicitly chosen a vs_runtime via config.)
if not has_config("vs_runtime") then
    set_runtimes("MD")
end

-- Option to skip the LeviLamina SDK dependency (e.g. when only building the
-- standalone protocol tests without the BDS toolchain / github access).
-- Usage:  xmake f --sdk=n  &&  xmake build novachat-levilamina-tests
option("sdk")
    set_default("y")
    set_showmenu(true)
    set_description("Enable the LeviLamina SDK dependency (requires VS + github).")
option_end()

-- NOTE: `xmake f --sdk=n` stores the option as the BOOLEAN false, not the
-- string "n" (xmake converts y/n option values). Comparing against "n" alone
-- therefore never matched and the SDK branch was always taken, so
-- `xmake f --sdk=n` still cloned the levimc repo and pulled levilamina. Accept
-- both spellings here and reuse the predicate for the target guards below.
local sdk_disabled = (get_config("sdk") == "n") or (get_config("sdk") == false)

-- Option to skip the OpenSSL package entirely.
--
-- OpenSSL is only needed by the TLS transport tests and the BDS plugin target;
-- the pure protocol/i18n/golden tests link neither. A host without a
-- Windows-native Perl cannot build the OpenSSL package from source (its
-- Configure script rejects a cygwin Perl), so those runs need a way to
-- configure and build the pure tests anyway. Defaults to on, so every existing
-- invocation behaves exactly as before.
-- Usage:  xmake f --sdk=n --openssl=n  &&  xmake build novachat-levilamina-tests
option("openssl")
    set_default("y")
    set_showmenu(true)
    set_description("Enable the OpenSSL package (TLS transport + TLS tests).")
option_end()

-- Same y/n -> boolean coercion as the sdk option above.
local openssl_disabled = (get_config("openssl") == "n") or (get_config("openssl") == false)

if not sdk_disabled then
    -- LeviLamina SDK + official build rules (only pulled when --sdk=n is NOT
    -- passed). set_policy("network.skip_fetch", true) would block the package
    -- pull, so we rely on the option guard above to avoid the repo clone
    -- entirely when only the test target is built.
    add_repositories("levimc-repo https://github.com/LiteLDev/xmake-repo.git")
    -- Pin the SDK to the version this plugin was validated against.
    --
    -- An unpinned add_requires("levilamina") resolves to the newest published
    -- release on every fresh CI run. That silently floated us from the
    -- validated 26.20.7 to 26.51.7, which no longer compiles against the MSVC
    -- 14.44 STL that windows-2022 ships: its <memory> instantiates
    -- default_delete<CommandRegistry> etc. over forward-declared types
    -- (MinecraftCommands.h holds a unique_ptr to an incomplete CommandRegistry),
    -- so the build dies in the SDK's own sources with
    --   error: invalid application of 'sizeof' to an incomplete type
    -- before any NovaChat code is compiled. The failure is entirely inside the
    -- dependency, so there is nothing to fix on our side -- only the version to
    -- hold still. Bump this deliberately, together with the runner image, after
    -- confirming the new SDK builds under that image's toolset.
    add_requires("levilamina 26.20.7", {configs = {target_type = "server"}})
    -- levibuildscript provides the linkrule + modpacker rules; it is a pure-Lua
    -- rules package (no compile step), so it needs no version pin.
    add_requires("levibuildscript")
    -- AUTH-002 TLS: OpenSSL is the TLS backend for the transport encryption
    -- (SSL_CTX + non-blocking SSL_connect/SSL_read/SSL_write wired into the
    -- select() loop in NetworkClient). Declared here so the symbols link once
    -- the SSL state machine lands in doConnect(). The self-contained
    -- HmacSha256/Sha256 utilities do NOT depend on OpenSSL (they have their
    -- own bundled implementations); this dependency is transport-only.
    if not openssl_disabled then
        add_requires("openssl")
    end
end

-- Target: novachat-levilamina (the BDS plugin shared library)
target("novachat-levilamina")
    -- LeviLamina official link + mod-packing rules. Applied only when the SDK
    -- is enabled (the rules come from the levibuildscript package). linkrule
    -- handles the LeviLamina DLL link semantics; modpacker packs manifest.json
    -- into the output (replaces the manual after_build os.cp below -- kept as a
    -- fallback in case modpacker is absent in a future levibuildscript version).
    if not sdk_disabled then
        add_rules("@levibuildscript/linkrule")
        add_rules("@levibuildscript/modpacker")
    end
    add_cxflags("/EHa", "/utf-8", "/W4", "/w44265", "/w44289", "/w44296", "/w45263", "/w44738", "/w45204")
    add_defines("NOMINMAX", "UNICODE", "_UNICODE")
    set_kind("shared")
    set_languages("c++20")
    set_symbols("debug")

    -- LeviLamina dependency (only when the SDK is enabled).
    if not sdk_disabled then
        add_packages("levilamina")
        -- AUTH-002 TLS: expose the OpenSSL headers + link the OpenSSL runtime
        -- to the plugin target. Only added under the SDK guard (the test
        -- targets do not use the TLS transport and must stay free of the BDS
        -- toolchain). Used by the forthcoming SSL state machine in
        -- NetworkClient; harmless until then (an unused package import).
        if not openssl_disabled then
            add_packages("openssl")
        end
    end

    -- Source files
    add_files("src/**.cpp")
    add_headerfiles("src/**.h")
    add_includedirs("src")

    -- Output configuration
    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina.dll")

    -- Windows-specific settings
    if is_plat("windows") then
        add_defines("WIN32", "_WIN32", "_WINDOWS")
        -- AUTH-002 TLS: link the OpenSSL libraries alongside the existing
        -- winsock / advapi32 system libs. The openssl xmake package (declared
        -- above) provides the import libs via add_packages; add_syslinks
        -- keeps the link line explicit and matches the idiom of the existing
        -- ws2_32/mswsock/advapi32 entries. The TLS transport is verified by
        -- OpenSSL (SSL_VERIFY_PEER) — there is no option to disable cert
        -- verification once TLS is enabled.
        add_syslinks("ws2_32", "mswsock", "advapi32", "libssl", "libcrypto")
    end

    -- After build: copy manifest + resources/lang (fallback modpacker; harmless
    -- if modpacker already packed them -- os.cp just overwrites with the same
    -- files). The lang/ directory ships next to the .dll so the I18n loader
    -- (which scans <module-dir>/lang/*.json) finds translations at runtime.
    --
    -- manifest.json MUST NOT declare a "dependencies": [{"name":"LeviLamina"}]
    -- entry. LeviLamina's own installed manifest (plugins/LeviLamina/
    -- manifest.json) sets "type": "preload-native", which equals
    -- pl::pl_mod_manager_name, so ModRegistrar::loadManifest() early-returns
    -- and LeviLamina is never entered into the manifest map that
    -- ModLoadPlanner::resolve() searches. A dependency on "LeviLamina" can
    -- therefore NEVER resolve -> MissingDependency -> the mod is rejected and
    -- the server logs "novachat-levilamina will not be loaded because
    -- dependency LeviLamina is missing" + "0 mod(s) loaded" even though the
    -- loader itself is running. The official LiteLDev/levilamina-mod-template
    -- manifest likewise declares only name/entry/version/type/platform. The
    -- plugin still links against LeviLamina at build time via add_packages
    -- above; only the runtime manifest dependency is dropped.
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("manifest.json", targetdir)
        os.cp("resources", targetdir .. "/resources")
        os.cp("src/i18n/lang", targetdir .. "/lang")
    end)
target_end()

-- Test target: standalone protocol round-trip tests (no LeviLamina SDK needed).
-- Only compiles the pure-C++ protocol/util/i18n sources, so it can run in CI
-- without the BDS toolchain or github access.
--
-- Build & run:
--   xmake f --sdk=n -m debug
--   xmake build novachat-levilamina-tests
--   xmake run novachat-levilamina-tests
target("novachat-levilamina-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    -- /utf-8: tell MSVC + clang-cl the source + execution charset are UTF-8.
    -- tests/test_protocol.cpp contains non-ASCII string literals (Chinese,
    -- e.g. "已加入频道") saved as UTF-8 without BOM. Without /utf-8, a
    -- compiler on a non-UTF-8 system locale (e.g. Chinese Windows codepage
    -- 936/GBK) parses the UTF-8 bytes as GBK -> mojibake -> C2001 "newline
    -- in constant" + C4819 "file contains characters not representable in
    -- current code page (936)". clang-cl is stricter than MSVC here: MSVC
    -- on a CJK locale silently auto-detects and tolerates it, clang-cl
    -- does not. /utf-8 (= /source-charset:utf-8 /execution-charset:utf-8)
    -- is accepted by BOTH MSVC and clang-cl (clang-cl maps it internally)
    -- so the tests compile identically regardless of host locale. This
    -- matches the /utf-8 already on the novachat-levilamina target above.
    add_cxflags("/utf-8")
    add_includedirs("src")

    -- Test runner + pure-C++ sources only (no ll:: includes).
    add_files("tests/test_protocol.cpp")
    add_files("src/protocol/PacketBuffer.cpp")
    add_files("src/util/Sha256.cpp")
    add_files("src/util/HmacSha256.cpp")
    add_files("src/i18n/I18n.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
        add_syslinks("ws2_32", "advapi32")
    end

    -- Copy the lang/ resource directory next to the test binary so the I18n
    -- loader (which scans <exe-dir>/lang/*.json) finds the translations
    -- regardless of the current working directory. Mirrors how the real BDS
    -- plugin ships lang/ next to its .dll at runtime.
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("src/i18n/lang", targetdir .. "/lang")
    end)
target_end()

-- Test target: cross-language golden byte verification (no LeviLamina SDK
-- needed). Decodes the Java-generated frames under <repo>/test/protocol-golden
-- with the C++ protocol classes and re-encodes them byte-for-byte.
--
-- The golden directory is located at runtime: NOVALINK_REPO_ROOT env var, or
-- walking up from the working directory to the repo root (settings.gradle).
-- `xmake run` executes from the project dir, so no configuration is needed.
--
-- Build & run:
--   xmake f --sdk=n -m debug
--   xmake build novachat-levilamina-golden-tests
--   xmake run novachat-levilamina-golden-tests
target("novachat-levilamina-golden-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    -- /utf-8: the golden spot checks contain UTF-8 Chinese/emoji literals
    -- (same rationale as novachat-levilamina-tests above).
    add_cxflags("/utf-8")
    add_includedirs("src")

    add_files("tests/test_protocol_golden.cpp")
    add_files("src/protocol/PacketBuffer.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-golden-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
    end
target_end()

-- Both targets below link the OpenSSL package. They are declared inside a
-- function so --openssl=n simply skips them without touching the target
-- bodies (a host without Windows-native Perl cannot build the package).
local function defineOpensslTestTargets()
-- Test target: AUTH-002 TLS transport integration tests (no LeviLamina SDK
-- needed). Starts a mock TLS server using OpenSSL (SSL_CTX + SSL_accept) and
-- verifies the NetworkClient TLS state machine: handshake success with a
-- trusted CA, handshake failure with an untrusted CA, and handshake failure
-- with a hostname mismatch.
--
-- The NetworkClient TLS code links against the same OpenSSL runtime via
-- add_syslinks("libssl", "libcrypto"). The test file supplies its own mock
-- server SSL_CTX so no BDS/plugin runtime is needed.
--
-- Build & run:
--   xmake f --sdk=n -m debug
--   xmake build novachat-levilamina-tls-tests
--   xmake run novachat-levilamina-tls-tests
target("novachat-levilamina-tls-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    -- /utf-8: the test source contains UTF-8 literals (same rationale as the
    -- protocol tests above).
    add_cxflags("/utf-8")
    add_includedirs("src")

    -- OpenSSL headers + import libs for both the NetworkClient TLS transport
    -- and the mock TLS server inside the test.
    add_packages("openssl")

    add_files("tests/test_network_client_tls.cpp")
    add_files("src/network/NetworkClient.cpp")
    add_files("src/protocol/PacketBuffer.cpp")
    add_files("src/util/Sha256.cpp")
    add_files("src/util/HmacSha256.cpp")
    add_files("src/i18n/I18n.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-tls-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
        add_syslinks("ws2_32", "advapi32", "libssl", "libcrypto")
    end

    -- Copy the TLS fixture directory next to the test binary so the test can
    -- find the self-signed certs regardless of the cwd xmake launches from.
    -- Mirrors the lang/ copy on the protocol tests target above.
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("tests/tls", targetdir .. "/tls")
    end)
target_end()

-- Test target: PROTO-001 partial-write acceptance tests for the sendLoop()
-- framed-buffer + DrainResult path. These tests verify that a non-blocking
-- socket send that returns a partial write (short write), zero write,
-- WOULDBLOCK/EAGAIN, or a fatal error (ECONNRESET) never truncates, reorders
-- or drops packets — the acceptance criteria for PROTO-001.
--
-- The tests install a deterministic send hook via setSendHookForTest() so
-- they can inject each outcome on demand without a real socket or the
-- network thread. The "production path regression" scenario at the end
-- guards that the seam is a no-op when no hook is installed (mSendHook ==
-- null -> doRawSend falls through to ::send / tlsSend exactly as before).
--
-- Build & run:
--   xmake f --sdk=n -m debug
--   xmake build novachat-levilamina-send-tests
--   xmake run novachat-levilamina-send-tests
target("novachat-levilamina-send-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    -- /utf-8: the test source contains UTF-8 literals (same rationale as the
    -- protocol tests above).
    add_cxflags("/utf-8")
    add_includedirs("src")

    -- OpenSSL: NetworkClient.cpp's TLS code path pulls in <openssl/ssl.h>;
    -- the partial-write tests exercise the plaintext branch but the TUs must
    -- still link the OpenSSL import libs to resolve tlsSend/tlsRecv symbols.
    add_packages("openssl")

    add_files("tests/test_network_send_partial.cpp")
    add_files("src/network/NetworkClient.cpp")
    add_files("src/protocol/PacketBuffer.cpp")
    add_files("src/util/Sha256.cpp")
    add_files("src/util/HmacSha256.cpp")
    add_files("src/i18n/I18n.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-send-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
        add_syslinks("ws2_32", "advapi32", "libssl", "libcrypto")
    end

    -- Copy the lang/ resource directory next to the test binary so the I18n
    -- loader (which scans <exe-dir>/lang/*.json) finds the translations
    -- regardless of the current working directory. Mirrors how the real BDS
    -- plugin ships lang/ next to its .dll at runtime.
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("src/i18n/lang", targetdir .. "/lang")
    end)
target_end()

end

if not openssl_disabled then
    defineOpensslTestTargets()
end

-- Test target: VERIFY-005 LeviLamina packet-decode fuzz tests (no LeviLamina
-- SDK needed). Fuzzes the pure decode boundaries (VarInt::tryPeek/decode,
-- PacketBuffer::readString, Packet::read) for the four audit scenarios:
-- unknown packet ID, bad VarInt, bad UTF-8/truncated field, oversized field.
--
-- The test re-implements the decodePacket switch inline so it does NOT depend
-- on the private NetworkClient::processReceivedData / decodePacket methods.
-- No production source is touched (no test seam extraction needed — the pure
-- decode functions are already public). See the test header comment for the
-- honest residual-gap note about the "close connection" audit expectation.
--
-- Build & run:
--   xmake f --sdk=n -m debug
--   xmake build novachat-levilamina-decode-fuzz-tests
--   xmake run novachat-levilamina-decode-fuzz-tests
target("novachat-levilamina-decode-fuzz-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    add_cxflags("/utf-8")
    add_includedirs("src")

    add_files("tests/test_network_decode_fuzz.cpp")
    add_files("src/protocol/PacketBuffer.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-decode-fuzz-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
        add_syslinks("ws2_32", "advapi32")
    end
target_end()

-- Test target: VERIFY-007 LeviLamina i18n error-code parity regression test
-- (no LeviLamina SDK needed). Asserts both en_US and zh_CN bundles contain
-- the 26 canonical NC-* error codes (message + suggestion keys) and that
-- errorMessage() composites a non-empty localized string.
--
-- Build & run:
--   xmake f --sdk=n -m debug
--   xmake build novachat-levilamina-i18n-parity-tests
--   xmake run novachat-levilamina-i18n-parity-tests
target("novachat-levilamina-i18n-parity-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    add_cxflags("/utf-8")
    add_includedirs("src")

    add_files("tests/test_i18n_errorcode_parity.cpp")
    add_files("src/protocol/PacketBuffer.cpp")
    add_files("src/i18n/I18n.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-i18n-parity-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
        add_syslinks("ws2_32", "advapi32")
    end

    -- Copy the lang/ resource directory next to the test binary so the I18n
    -- loader (which scans <exe-dir>/lang/*.json) finds the translations.
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("src/i18n/lang", targetdir .. "/lang")
    end)
target_end()

-- Test target: moderation command packet-contract tests (no LeviLamina SDK
-- needed). Pins what /nc mute / kick / ban / unban put on the wire: the
-- ChannelAction id, the operator/target/duration extras the backend reads,
-- the console sentinel UUID + console=true pair, the permission rejection and
-- the argument validation.
--
-- The builders under src/command/ModerationCommands.cpp depend only on the
-- protocol layer, so the exact code the plugin runs is compiled here without
-- ll::/mc:: headers. CommandHandler.cpp itself stays untested in this target:
-- it is the thin adapter over these builders and needs the BDS toolchain.
--
-- Build & run:
--   xmake f --sdk=n --openssl=n -m debug
--   xmake build novachat-levilamina-moderation-tests
--   xmake run novachat-levilamina-moderation-tests
target("novachat-levilamina-moderation-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    add_cxflags("/utf-8")
    add_includedirs("src")

    add_files("tests/test_moderation_commands.cpp")
    add_files("src/command/ModerationCommands.cpp")
    add_files("src/protocol/PacketBuffer.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-moderation-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
        add_syslinks("ws2_32", "advapi32")
    end
target_end()

-- Test target: moderation-response correlation tests (no LeviLamina SDK
-- needed). Pins the operator-facing outcome of a ChannelActionResponse:
-- a correlated success renders the per-action confirmation, a correlated
-- failure renders the localized error, an uncorrelated response renders
-- nothing, and a failure with a blank error code stays silent.
--
-- The logic lives in src/command/ModerationOutcome.cpp, which depends only on
-- the protocol layer (same seam as the ModerationCommands builders), so the
-- exact code ChatInterceptor runs is exercised here without ll::/mc:: headers.
--
-- Build & run:
--   xmake f --sdk=n --openssl=n -m debug
--   xmake build novachat-levilamina-moderation-response-tests
--   xmake run novachat-levilamina-moderation-response-tests
target("novachat-levilamina-moderation-response-tests")
    set_kind("binary")
    set_languages("c++20")
    set_symbols("debug")
    add_cxflags("/utf-8")
    add_includedirs("src")

    add_files("tests/test_moderation_response.cpp")
    add_files("src/command/ModerationOutcome.cpp")
    -- The send-side half of the test builds a real packet through the same
    -- builder the plugin uses, so the request id it registers is the one the
    -- response will echo.
    add_files("src/command/ModerationCommands.cpp")
    add_files("src/protocol/PacketBuffer.cpp")
    add_files("src/i18n/I18n.cpp")

    set_targetdir("$(buildir)/bin")
    set_filename("novachat-levilamina-moderation-response-tests.exe")

    if is_plat("windows") then
        add_defines("NOMINMAX", "UNICODE", "_UNICODE", "WIN32", "_WIN32", "_WINDOWS")
        add_syslinks("ws2_32", "advapi32")
    end

    -- Copy the lang/ directory next to the test binary so the I18n loader
    -- finds the bundles (same rationale as the moderation-tests target).
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("src/i18n/lang", targetdir .. "/lang")
    end)
target_end()
