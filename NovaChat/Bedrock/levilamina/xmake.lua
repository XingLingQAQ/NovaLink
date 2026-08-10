-- NovaChat-LeviLamina Build Configuration
-- xmake build system for LeviLamina (BDS) plugin

add_rules("mode.debug", "mode.release")

-- Option to skip the LeviLamina SDK dependency (e.g. when only building the
-- standalone protocol tests without the BDS toolchain / github access).
-- Usage:  xmake f --sdk=n  &&  xmake build novachat-levilamina-tests
option("sdk")
    set_default("y")
    set_showmenu(true)
    set_description("Enable the LeviLamina SDK dependency (requires VS + github).")
option_end()

if get_config("sdk") ~= "n" then
    -- LeviLamina SDK (only pulled when --sdk=n is NOT passed).
    -- set_policy("network.skip_fetch", true) would block the package pull,
    -- so we rely on the option guard above to avoid the repo clone entirely
    -- when only the test target is built.
    add_repositories("liteldev-repo https://github.com/LiteLDev/xmake-repo.git")
    add_requires("levilamina")
end

-- Target: novachat-levilamina (the BDS plugin shared library)
target("novachat-levilamina")
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

    -- After build: copy manifest
    after_build(function (target)
        local targetdir = target:targetdir()
        os.cp("manifest.json", targetdir)
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
target_end()
