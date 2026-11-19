package com.punchytacz.anim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * TACZ 的内置兜底动画。
 * <p>
 * 这套近战包的状态机脚本引用了 {@code idle / run_hold / walk_forward / walk_backward /
 * walk_sideway} 等名字，但**包内动画文件都不定义它们**——它们是 TACZ 的内置动画
 * （{@code tacz:animations/pistol_default.animation.json} 与
 * {@code tacz:animations/rifle_default.animation.json}，只动 {@code root/camera/constraint} 三根骨骼）。
 * 原版 TACZ 的 {@code runAnimation("run_hold")} 会解析到内置 clip；我们不加载内置动画就探测不到，
 * {@code runAnimation} 静默 no-op，导致起跳/落地时奔跑相位跳变。
 * <p>
 * 规则：**包内定义优先**，内置 clip 只作为兜底注册（{@link AnimationController#providePrototypeIfAbsent}）。
 * 骨骼名对不上的通道由 {@code BedrockListeners.supply} 返回 null、
 * {@link ObjectAnim#applyAnimationListeners} 跳过 —— 即按 vanilla 的“未知骨骼静默忽略”处理。
 * <p>
 * 文件来源：TACZ-Refabricated（GPL-3.0），本模组同为 GPL-3.0-only，许可兼容；
 * 优先尝试 {@code tacz:} 命名空间（TACZ 已安装时用原文件），失败则回落到本模组内嵌副本
 * （{@code punchytacz:animations/tacz_builtin_*.animation.json}）。
 */
public final class TaczBuiltInAnimations {
    private static final Logger LOG = LoggerFactory.getLogger("punchytacz");
    /** 依次尝试的资源；tacz 命名空间在本实例可能不存在（TACZ 未安装），故带内嵌副本兜底 */
    private static final String[][] SOURCES = {
            {"tacz", "animations/pistol_default.animation.json"},
            {"tacz", "animations/rifle_default.animation.json"},
            {"punchytacz", "animations/tacz_builtin_pistol.animation.json"},
            {"punchytacz", "animations/tacz_builtin_rifle.animation.json"},
    };

    private static volatile List<ObjectAnim> cached;
    private static ResourceManager cachedManager;
    private static boolean warned;

    private TaczBuiltInAnimations() {
    }

    /** 把内置 clip 注册为兜底（包内已有的名字不覆盖） */
    public static void applyFallbacks(AnimationController controller, ResourceManager manager) {
        for (ObjectAnim anim : load(manager)) {
            controller.providePrototypeIfAbsent(anim.name, () -> anim);
        }
    }

    private static List<ObjectAnim> load(ResourceManager manager) {
        List<ObjectAnim> cachedList = cached;
        if (cachedList != null && cachedManager == manager) {
            return cachedList;
        }
        List<ObjectAnim> result = new ArrayList<>();
        List<String> loaded = new ArrayList<>();
        for (String[] source : SOURCES) {
            Identifier id = Identifier.fromNamespaceAndPath(source[0], source[1]);
            // 同名 clip 只取第一份（pistol 与 rifle 有重复定义）
            List<String> names = result.stream().map(a -> a.name).toList();
            try {
                Resource resource = manager.getResource(id).orElse(null);
                if (resource == null) {
                    continue;
                }
                try (Reader reader = resource.openAsReader()) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    for (ObjectAnim anim : BedrockAnimationLoader.parse(json)) {
                        if (anim != null && !names.contains(anim.name)) {
                            result.add(anim);
                            loaded.add(anim.name);
                        }
                    }
                }
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    LOG.warn("punchytacz: 读取内置兜底动画失败 {}", id, t);
                }
            }
        }
        LOG.info("punchytacz: 已注册 {} 个内置兜底动画 {}", result.size(), loaded);
        cachedManager = manager;
        cached = List.copyOf(result);
        return cached;
    }
}
