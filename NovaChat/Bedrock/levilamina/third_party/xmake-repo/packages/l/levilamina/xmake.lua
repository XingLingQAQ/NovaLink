-- Local override of the levimc-repo "levilamina" package.
--
-- WHY THIS EXISTS
-- ---------------
-- 26.51.7 does not compile under the MSVC 14.44 STL that windows-2022 ships.
-- Between v26.20.7 and v26.51.0 upstream rewrote a batch of destructors from
-- out-of-line declarations into in-class `= default` definitions:
--
--   v26.20.7:  virtual ~MinecraftCommands();
--   v26.51.0+: virtual ~MinecraftCommands() = default;
--
-- That only breaks where the class owns a std::unique_ptr to a type the same
-- header merely forward-declares: defaulting the destructor in the class body
-- makes the compiler instantiate std::default_delete<T> right there, and MSVC's
-- <memory> static_asserts `0 < sizeof(_Ty)`:
--
--   memory(3308,27): error: invalid application of 'sizeof' to an incomplete type 'CommandRegistry'
--   MinecraftCommands.h(56,13): note: in defaulted destructor for 'MinecraftCommands' first required here
--   ExecuteCommandEvent.cpp(37,5): note: ... first required here
--
-- The same shape recurs across the SDK. 26.20.7 compiles and 26.51.7 does not,
-- and the set is exactly measurable: of the 601 destructors that became
-- `= default` between those tags, 83 sit on a class that holds a unique_ptr to
-- a forward-declared type in its own header: 83 lines across 80 files.
--
-- The patch restores those 83 declarations to their v26.20.7 text verbatim --
-- no other change, and every member type stays forward-declared. An unevaluated
-- declaration is enough to compile and link because nothing odr-uses these
-- destructors: the generated hooks derive from the SDK types but never destroy
-- one, and 26.20.7's shipped DLL imports only compileCommand/executeCommand for
-- MinecraftCommands, carrying no destructor import at all. The remaining 1518
-- `= default` destructors are untouched -- they either own complete types or are
-- never instantiated, which is why only these 83 show up in the build.
--
-- Upstream never fixed this: v26.51.8 and main still declare both examples
-- above as `= default`. Treat the patch as a workaround for a third-party
-- defect, not a permanent fork, and drop it once a published 26.51.x builds.

package("levilamina")
    add_urls("https://github.com/LiteLDev/LeviLamina.git")
    add_versionfiles("versions/versions.txt")

    add_defines("ENTT_PACKED_PAGE=128", "ENTT_SPARSE_PAGE=4096", "ENTT_NO_MIXIN")

    add_configs("target_type", {default = "server", values = {"server", "client"}})

    -- Range starts at 26.51.0, where the destructor rewrite landed. 26.20.7 and
    -- earlier already carry the out-of-line form, so patching them would fail.
    add_patches(">=26.51.0", path.join(os.scriptdir(), "patches", "26.51-mc-dtors.patch"),
        "a2dc953e5a2769dd6b4e2bef17a1ba3b5761a5ff5b4d639a05915c69c057ee64")

    on_load(function(package)
        import("core.base.semver")
        local version = package:version_str()
        local sem = semver.try_parse(version)
        if sem and sem:le("0.12.4") then
            version = "old"
        end
        version = string.gsub(version, "%.", "_")
        try { function()
            import("versions." .. version).load(package)
        end, catch { function(e)
            cprint(
                "${bright yellow}warning: ${clear}Unknown version: ${bright cyan}"
                .. version .. "${clear}, will use main branch dependencies."
            )
            import("versions.main").load(package)
        end } }
        if package:config("target_type") == "server" then
            package:add("defines", "LL_PLAT_S")
        else
            package:add("defines", "LL_PLAT_C")
        end
    end)

    on_install(function(package)
        if package:config("target_type") == "server" then
            import("package.tools.xmake").install(package)
        else
            import("package.tools.xmake").install(package, {"--target_type=client"})
        end
    end)
