package com.punchytacz.lua;

import com.punchytacz.anim.AnimationController;
import com.punchytacz.statemachine.AnimationState;
import com.punchytacz.statemachine.AnimationStateContext;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 照抄 TACZ 的 {@code LuaStateMachineFactory}。
 * <p>
 * 脚本根表约定：
 * <ul>
 *   <li>{@code initialize(self, context)} —— 可选</li>
 *   <li>{@code exit(self, context)} —— 可选</li>
 *   <li>{@code states(self)} —— **必需**，返回一组初始状态表；TACZ 里就是
 *       base / main / movement 三条轨道各一个，状态机把它们**并行**维护</li>
 * </ul>
 */
public final class LuaStateMachineFactory<T extends AnimationStateContext> {
    private AnimationController controller;
    private LuaFunction initializeFunc;
    private LuaFunction exitFunc;
    private LuaFunction statesFunc;
    private LuaTable table;

    public LuaAnimationStateMachine<T> build() {
        if (controller == null) {
            throw new IllegalStateException("controller must not be null before build");
        }
        if (statesFunc == null) {
            throw new IllegalStateException("lua script has no 'states' function");
        }
        LuaAnimationStateMachine<T> stateMachine = new LuaAnimationStateMachine<>(controller);
        stateMachine.initializeFunc = context -> {
            if (initializeFunc != null) {
                initializeFunc.call(table, CoerceJavaToLua.coerce(context));
            }
        };
        stateMachine.exitFunc = context -> {
            if (exitFunc != null) {
                exitFunc.call(table, CoerceJavaToLua.coerce(context));
            }
        };
        stateMachine.setStatesSupplier(getStatesSupplier());
        return stateMachine;
    }

    public LuaStateMachineFactory<T> setController(AnimationController controller) {
        this.controller = controller;
        return this;
    }

    public LuaStateMachineFactory<T> setLuaScripts(LuaTable table) {
        if (table == null) {
            return this;
        }
        this.table = table;
        this.initializeFunc = checkFunction("initialize", table);
        this.exitFunc = checkFunction("exit", table);
        this.statesFunc = checkFunction("states", table);
        return this;
    }

    private LuaFunction checkFunction(String funcName, LuaTable table) {
        LuaValue value = table.get(funcName);
        if (value.isnil()) {
            return null;
        }
        return value.checkfunction();
    }

    private Supplier<Iterable<? extends AnimationState<T>>> getStatesSupplier() {
        if (statesFunc == null) {
            return null;
        }
        return () -> {
            LuaTable statesTable = statesFunc.call(table).checktable();
            List<LuaAnimationState<T>> states = new ArrayList<>();
            for (int i = 1; i <= statesTable.length(); i++) {
                states.add(new LuaAnimationState<>(statesTable.get(i).checktable(), table));
            }
            return states;
        };
    }
}
