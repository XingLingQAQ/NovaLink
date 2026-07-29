-- NovaChat-LeviLamina Build Configuration
-- xmake build system for LeviLamina (BDS) plugin

add_rules("mode.debug", "mode.release")

-- LeviLamina SDK
add_repositories("liteldev-repo https://github.com/LiteLDev/xmake-repo.git")
add_requires("levilamina")

-- Target: novachat-levilamina
target("novachat-levilamina")
    add_cxflags("/EHa", "/utf-8", "/W4", "/w44265", "/w44289", "/w44296", "/w45263", "/w44738", "/w45204")
    add_defines("NOMINMAX", "UNICODE", "_UNICODE")
    set_kind("shared")
    set_languages("c++20")
    set_symbols("debug")
    
    -- LeviLamina dependency
    add_packages("levilamina")
    
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
