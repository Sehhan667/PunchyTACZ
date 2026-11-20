package com.punchytacz;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 本模组自己的武器选择表。
 * <p>
 * 键有两种前缀，查找顺序是「物品 → 类型」：
 * <ul>
 *   <li>{@code item:<物品 id>} —— 只对这一把物品生效</li>
 *   <li>{@code kind:<Punchy kind>} —— 对这一类物品生效（如 {@code kind:SWORD}）</li>
 * </ul>
 * 值是被选中的武器 id（见 {@code assets/punchytacz/weapons/<id>.json}）。
 * <p>
 * 和 Punchy 的槽位选择完全分开：Punchy 那边只负责「宿主包 = 开 / 其它包 = 关」。
 */
public final class TaczSelection {
    private static final Logger LOG = LoggerFactory.getLogger("punchytacz");
    private static final String FILE_NAME = "punchytacz-selection.properties";

    private static final Properties BY_KEY = new Properties();
    private static boolean loaded;

    private TaczSelection() {
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path p = path();
        if (!Files.exists(p)) {
            return;
        }
        try (InputStream in = Files.newInputStream(p)) {
            BY_KEY.load(in);
        } catch (Exception e) {
            LOG.warn("读取武器选择失败", e);
        }
    }

    public static String get(String key) {
        load();
        String value = BY_KEY.getProperty(key);
        return value == null || value.isBlank() ? null : value;
    }

    public static void put(String key, String weaponId) {
        load();
        if (weaponId == null || weaponId.isBlank()) {
            BY_KEY.remove(key);
        } else {
            BY_KEY.setProperty(key, weaponId);
        }
        save();
    }

    /** 只对当前这把物品生效的设置（用来在「仅此物品」模式下写值） */
    public static String itemKey(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return "item:" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
    }

    /**
     * 统一大写：Punchy 的槽位 id 是 {@code kind:sword}（枚举名小写），
     * 而 {@code PunchyApi.kindOf} 返回 {@code SWORD}。两边必须归一，
     * 否则配置里存的和同步时查的不是同一个键（「仅此物品/整类」会互相看不到）。
     */
    public static String kindKey(String kind) {
        return "kind:" + (kind == null ? "UNKNOWN"
                : kind.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 「Punchy 独有状态」的动画映射，键写在 config 里：
     * {@code state:<武器 id>:<Punchy clip 名> = <包内动画名>}，例如
     * {@code state:wt:melee/observer:swiming = swim}。支持前缀匹配（键比 clip 名短也算命中）。
     * 没有配置返回 null，调用方就什么都不做。
     */
    public static String stateAnimFor(String weaponId, String clipName) {
        load();
        if (weaponId == null || clipName == null) {
            return null;
        }
        String prefix = "state:" + weaponId + ":";
        String exact = BY_KEY.getProperty(prefix + clipName);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        for (String name : BY_KEY.stringPropertyNames()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            String key = name.substring(prefix.length());
            if (key.isEmpty()) {
                continue;
            }
            if (clipName.startsWith(key) || key.startsWith(clipName)) {
                String value = BY_KEY.getProperty(name);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 所有键（预热时遍历用） */
    public static java.util.Set<String> keys() {
        load();
        return java.util.Set.copyOf(BY_KEY.stringPropertyNames());
    }

    public static void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (OutputStream out = Files.newOutputStream(p)) {
                BY_KEY.store(out, "PunchyTACZ weapon selection");
            }
        } catch (Exception e) {
            LOG.warn("保存武器选择失败", e);
        }
    }
}
