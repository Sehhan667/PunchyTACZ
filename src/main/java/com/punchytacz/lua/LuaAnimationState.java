package com.punchytacz.lua;

import com.punchytacz.statemachine.AnimationState;
import com.punchytacz.statemachine.AnimationStateContext;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

/**
 * 照抄 TACZ 的 {@code LuaAnimationState}。
 * <p>
 * 每个 state 是 lua 里的一个 table，带 update / entry / exit / transition 四个函数，
 * 调用签名都是 {@code (this, context[, input])}，其中 {@code this} 是**脚本根表**
 * —— 这就是 lua 里能写 {@code this.main_track_states.attack_index} 的原因。
 */
final class LuaAnimationState<T extends AnimationStateContext> implements AnimationState<T> {
    private final LuaTable stateTable;
    private final LuaTable scriptTable;
    private final LuaFunction updateFunction;
    private final LuaFunction enterFunction;
    private final LuaFunction exitFunction;
    private final LuaFunction transitionFunction;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("punchytacz");

    LuaAnimationState(LuaTable stateTable, LuaTable scriptTable) {
        this.stateTable = stateTable;
        this.scriptTable = scriptTable;
        this.updateFunction = checkLuaFunction("update");
        this.enterFunction = checkLuaFunction("entry");
        this.exitFunction = checkLuaFunction("exit");
        this.transitionFunction = checkLuaFunction("transition");
    }

    @Override
    public void update(T context) {
        if (updateFunction == null) {
            return;
        }
        try {
            updateFunction.call(scriptTable, CoerceJavaToLua.coerce(context));
        } catch (Throwable t) {
            LOG.warn("punchytacz: lua update 出错（脚本已降级，动画停住）", t);
        }
    }

    @Override
    public void entryAction(T context) {
        if (enterFunction == null) {
            return;
        }
        try {
            enterFunction.call(scriptTable, CoerceJavaToLua.coerce(context));
        } catch (Throwable t) {
            LOG.warn("punchytacz: lua entry 出错", t);
        }
    }

    @Override
    public void exitAction(T context) {
        if (exitFunction == null) {
            return;
        }
        try {
            exitFunction.call(scriptTable, CoerceJavaToLua.coerce(context));
        } catch (Throwable t) {
            LOG.warn("punchytacz: lua exit 出错", t);
        }
    }

    @Override
    public AnimationState<T> transition(T context, String condition) {
        if (transitionFunction == null) {
            return null;
        }
        try {
            LuaValue mainTable = scriptTable.get("main_track_states");
            int beforeIndex = mainTable.get("attack_index").optint(-1);
            int beforeRe = mainTable.get("re").optint(-1);
            LuaValue next = transitionFunction.call(scriptTable,
                    CoerceJavaToLua.coerce(context), LuaString.valueOf(condition));
            int afterIndex = mainTable.get("attack_index").optint(-1);
            if ("attack_left".equals(condition)) {
                LOG.info("punchytacz: attack_left 前(index={} re={}) 后(index={}) → {}",
                        beforeIndex, mainTable.get("re").tojstring(), afterIndex,
                        next.istable() ? "state" : "nil");
            } else if (beforeIndex > 0 && afterIndex == 0) {
                // 任何"把连击索引归零"的输入都打出来：正常只可能是 inspect / put_away / 超时
                LOG.info("punchytacz: 输入 '{}' 把 attack_index {} → 0（re {} → {}）",
                        condition, beforeIndex, beforeRe,
                        mainTable.get("re").tojstring());
            }
            if (next.istable()) {
                return new LuaAnimationState<>((LuaTable) next, scriptTable);
            }
            if (next.isnil()) {
                return null;
            }
            throw new LuaError("the return of function 'transition' must be table or nil");
        } catch (Throwable t) {
            // 脚本报错不能让渲染线程崩：停在当前状态
            LOG.warn("punchytacz: lua transition 出错，input={}", condition, t);
            return null;
        }
    }

    private LuaFunction checkLuaFunction(String name) {
        LuaValue value = stateTable.get(name);
        if (value.isfunction()) {
            return (LuaFunction) value;
        }
        if (value.isnil()) {
            return null;
        }
        throw new LuaError("the type of field '" + name + "' must be function or nil");
    }
}
