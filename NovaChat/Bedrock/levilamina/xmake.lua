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

if get_config("sdk") ~= "n" then
    -- LeviLamina SDK + official build rules (only pulled when --sdk=n is NOT
    -- passed). set_policy("network.skip_fetch", true) would block the package
    -- pull, so we rely on the option guard above to avoid the repo clone
    -- entirely when only the test target is built.
    add_repositories("levimc-repo https://github.com/LiteLDev/xmake-repo.git")
    add_requires("levilamina", {configs = {target_type = "server"}})
    add_requires("levibuildscript")
end

-- Target: novachat-levilamina (the BDS plugin shared library)
target("novachat-levilamina")
    -- LeviLamina official link + mod-packing rules. Applied only when the SDK
    -- is enabled (the rules come from the levibuildscript package). linkrule
    -- handles the LeviLamina DLL link semantics; modpacker packs manifest.json
    -- into the output (replaces the manual after_build os.cp below -- kept as a
    -- fallback in case modpacker is absent in a future levibuildscript version).
    if get_config("sdk") ~= "n" then
        add_rules("@levibuildscript/linkrule")
        add_rules("@levibuildscript/modpacker")
    end
    add_cxflags("/EHa", "/utf-8", "/W4", "/w44265", "/w44289", "/w44296", "/w45263", "/w44738", "/w45204")
    add_defines("NOMINMAX", "UNICODE", "_UNICODE")
    set_kind("shared")
    set_languages("c++20")
    set_symbols("debug")

    -- LeviLamina dependency (only when the SDK is enabled).
    if get_config("sdk") ~= "n" then
        add_packages("levilamina")
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
        add_syslinks("ws2_32", "mswsock", "advapi32")
    end

    -- After build: copy manifest + lang resources (fallback modpacker; harmless
    -- if modpacker already packed them -- os.cp just overwrites with the same
    -- files). The lang/ directory ships next to the .dll so the I18n loader
    -- (which scans <module-dir>/lang/*.json) finds translations at runtime.
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("manifest.json", targetdir)
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
