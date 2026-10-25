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
-- The safe set is therefore "changed to `= default` AND the class owns a
-- unique_ptr to an incompletely-declared type", matched with a regex that also
-- sees namespace-qualified forward declarations: 99 lines across 94 files in
-- src/ and src-server/. Each is restored to its v26.20.7 text verbatim.
--
-- The filter has to cut both ways, and CI taught me the second direction too.
-- Reverting ALL 377 changed destructors instead compiled cleanly and then failed
-- to link with exactly three symbols:
--
--   ??1Command@@UEAA@XZ        unresolved (Command::~Command)
--   ??1IDataOutput@@UEAA@XZ    unresolved
--   ??1IDataInput@@UEAA@XZ     unresolved
--
-- Those three are abstract bases with no incomplete-type members, so they never
-- needed the change; but something does destroy them, and as out-of-line
-- declarations they become imports that prelink cannot resolve. The shipped
-- 26.51.7 DLL imports none of the three, which confirms they are meant to stay
-- `= default`. Keeping them out is what makes this patch both compile and link.
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
        "d83ebdb1288e02f2fb579c5d17d161e5f98e9486913f8f3c51375175e9514979")

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
