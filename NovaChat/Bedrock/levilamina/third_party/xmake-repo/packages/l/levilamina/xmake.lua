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
-- The same shape recurs across the SDK, and guessing which ones break is a trap
-- worth recording: a first attempt selected only classes holding a unique_ptr to
-- a type forward-declared in the SAME header and found 83. CI then died on
-- RakPeer, whose ShadowBanList is declared as `namespace RakNet { class
-- ShadowBanList; }` -- same hazard, different spelling, missed by that filter.
--
-- So this patch does not try to predict which destructors matter. It reverts
-- every destructor in the server-compiled trees (src/ and src-server/, the two
-- xmake actually builds for target_type=server) that upstream turned from an
-- out-of-line declaration into `= default`: 377 lines across 332 files, each
-- restored to its v26.20.7 text verbatim. That is exactly the form upstream
-- shipped and tested at 26.20.7, so no judgement is involved. src-client/ is
-- excluded on purpose -- the server build never compiles it and its files do not
-- match the v26.51.7 tree the patch applies to.
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
        "7e00438d2a95f78b6b2fdb957e8f7a16d160a36f255ccae2717924fd8491941a")

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
