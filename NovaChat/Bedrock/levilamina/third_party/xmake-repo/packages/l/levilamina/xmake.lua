-- Local override of the levimc-repo "levilamina" package.
--
-- WHY THIS EXISTS
-- ---------------
-- LeviLamina 26.51.x declares `virtual ~MinecraftCommands() = default;` in
-- src/mc/server/commands/MinecraftCommands.h. That class holds std::unique_ptr
-- members pointing at CommandRegistry, CommandOutputSender, Command and
-- DeferredCommandBase -- all of which are only forward-declared in that header.
-- An in-class defaulted destructor makes the compiler instantiate
-- std::default_delete<T> for each of them right there, and MSVC's <memory>
-- static_asserts `0 < sizeof(_Ty)`. With an incomplete type that is a hard
-- error, so the SDK cannot build at all:
--
--   memory(3308,27): error: invalid application of 'sizeof' to an incomplete type 'CommandRegistry'
--   MinecraftCommands.h(56,13): note: in instantiation of member function 'std::unique_ptr<CommandRegistry>::~unique_ptr'
--   ExecuteCommandEvent.cpp(37,5): note: in defaulted destructor for 'MinecraftCommands' first required here
--   ... same for CommandOutputSender, DeferredCommandBase, Command
--
-- Upstream did not always write it that way. v26.20.7 has exactly
--   virtual ~MinecraftCommands();
-- (an out-of-line declaration) and that is what the compiler accepts, because
-- the destructor is then not required to be complete at each point of use. The
-- change to `= default` landed in v26.51.0 and is still present in v26.51.8 and
-- on main, so waiting for a fix is not an option.
--
-- The patch restores the v26.20.7 declaration; nothing else in the header
-- changes and the four member types stay forward-declared. The destructor is
-- never odr-used by the SDK -- the generated ExecutingCommandEventHook derives
-- from MinecraftCommands but never destroys one, and LeviLamina 26.20.7's
-- shipped DLL imports only compileCommand/executeCommand for this class with no
-- destructor import at all -- so an unevaluated declaration both compiles and
-- links.
--
-- This is a workaround for a defect in a third-party package, not a permanent
-- fork: drop it once upstream ships a buildable 26.51.x.

package("levilamina")
    add_urls("https://github.com/LiteLDev/LeviLamina.git")
    add_versionfiles("versions/versions.txt")

    add_defines("ENTT_PACKED_PAGE=128", "ENTT_SPARSE_PAGE=4096", "ENTT_NO_MIXIN")

    add_configs("target_type", {default = "server", values = {"server", "client"}})

    -- Applies to every 26.51.x we pin. Verified against 26.51.7 and 26.51.8
    -- (both declare the destructor inline); 26.20.7 and earlier already use the
    -- out-of-line form, so the range deliberately starts at 26.51.0.
    add_patches(">=26.51.0", path.join(os.scriptdir(), "patches", "26.51.7-outofline-dtor.patch"),
        "ceb8aaffa6f2628118156afa41c645e6b5dd2bb715cf375fa0b8867389750869")

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
