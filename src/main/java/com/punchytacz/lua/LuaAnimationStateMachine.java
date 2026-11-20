package com.punchytacz.lua;

import com.punchytacz.anim.AnimationController;
import com.punchytacz.statemachine.AnimationStateContext;
import com.punchytacz.statemachine.AnimationStateMachine;

import java.util.function.Consumer;

/**
 * 照抄 TACZ 的 {@code LuaAnimationStateMachine}：
 * initialize / exit 都先把控制权交给脚本，再走状态机的常规流程。
 * <p>
 * 顺序不能反：脚本的 {@code initialize} 里会 {@code ensureTrackLineSize} /
 * {@code ensureTracksAmount} 建轨道，而随后的 state entry 就会往这些轨道上播动画。
 */
public final class LuaAnimationStateMachine<T extends AnimationStateContext>
        extends AnimationStateMachine<T> {
    Consumer<T> initializeFunc;
    Consumer<T> exitFunc;

    LuaAnimationStateMachine(AnimationController controller) {
        super(controller);
    }

    @Override
    public void initialize() {
        if (initializeFunc != null && getContext() != null) {
            initializeFunc.accept(getContext());
        }
        super.initialize();
    }

    @Override
    public void exit() {
        if (exitFunc != null && getContext() != null) {
            exitFunc.accept(getContext());
        }
        super.exit();
    }
}
