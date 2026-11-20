package com.punchytacz.lua;

import com.punchytacz.anim.ObjectAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;
import org.luaj.vm2.lib.StringLib;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;

/**
 * 刀包自带 lua 状态机的加载器。
 * <p>
 * 完全照 TACZ 的 {@code ScriptManager}：
 * <ul>
 *   <li>同一套 {@link Globals}（JseBaseLib + PackageLib + Bit32Lib + TableLib + JseStringLib + JseMathLib，
 *       不开 io/os/luajava）</li>
 *   <li>脚本 {@code chunk.call()} 必须返回一个 table</li>
 *   <li>INPUT_* / LOOP / PLAY_ONCE_* 这些常量注入到 globals，脚本里直接引用</li>
 * </ul>
 * 之前是手工把 lua 翻译成 Java，翻译失真导致了一连串问题（连击只第一段、attack_right 播错动画、
 * 走路时攻击无效……）。现在直接跑原脚本。
 */
public final class LuaScript {
    private static final Logger LOG = LoggerFactory.getLogger("punchytacz");

    private static Globals globals;
    /** 照 TACZ 的 ScriptManager：脚本按 id 缓存、全局共享（只处理主手，不会有并发使用者） */
    private static final java.util.Map<Identifier, LuaTable> CACHE = new java.util.HashMap<>();

    private LuaScript() {
    }

    /** 资源重载时清缓存（脚本内容可能变了） */
    public static void invalidate() {
        CACHE.clear();
    }

    /** 加载并缓存脚本；失败返回 null */
    public static LuaTable get(Identifier id) {
        if (id == null) {
            return null;
        }
        if (CACHE.containsKey(id)) {
            return CACHE.get(id);
        }
        LuaTable table = load(id);
        CACHE.put(id, table);
        return table;
    }

    private static LuaTable load(Identifier id) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        if (rm == null) {
            return null;
        }
        Resource resource = rm.getResource(id).orElse(null);
        if (resource == null) {
            return null;
        }
        try (Reader reader = resource.openAsReader()) {
            LuaValue chunk = globals().load(reader, id.toString());
            LuaValue result = chunk.call();
            if (result.istable()) {
                return result.checktable();
            }
            LOG.warn("punchytacz: lua 脚本 {} 没有返回 table", id);
        } catch (Throwable t) {
            LOG.warn("punchytacz: 加载 lua 脚本失败 {}", id, t);
        }
        return null;
    }

    private static Globals globals() {
        if (globals != null) {
            return globals;
        }
        Globals g = new Globals();
        g.load(new JseBaseLib());
        g.load(new PackageLib());
        g.load(new Bit32Lib());
        g.load(new TableLib());
        g.load(new StringLib());
        // 不开 JseIoLib / JseOsLib / LuajavaLib，脚本没有文件与系统权限
        g.load(new JseMathLib());
        LoadState.install(g);
        LuaC.install(g);
        installConstants(g);
        globals = g;
        return g;
    }

    /** 照 TACZ 的 LuaAnimationConstant / LuaGunAnimationConstant：把常量直接放进全局环境 */
    private static void installConstants(Globals g) {
        // 输入名（TACZ GunAnimationConstant 里与近战相关的部分）
        g.set("INPUT_DRAW", LuaValue.valueOf("draw"));
        g.set("INPUT_PUT_AWAY", LuaValue.valueOf("put_away"));
        g.set("INPUT_INSPECT", LuaValue.valueOf("inspect"));
        g.set("INPUT_IDLE", LuaValue.valueOf("idle"));
        g.set("INPUT_WALK", LuaValue.valueOf("walk"));
        g.set("INPUT_RUN", LuaValue.valueOf("run"));
        // 播放类型：脚本里写 LOOP / PLAY_ONCE_STOP / PLAY_ONCE_HOLD，值是枚举序号
        for (ObjectAnim.PlayType type : ObjectAnim.PlayType.values()) {
            g.set(type.name(), LuaValue.valueOf(type.ordinal()));
        }
    }
}
