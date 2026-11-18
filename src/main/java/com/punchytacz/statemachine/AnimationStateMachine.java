package com.punchytacz.statemachine;

import com.punchytacz.anim.AnimationController;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 对应 TACZ AnimationStateMachine */
public class AnimationStateMachine<T extends AnimationStateContext> {
    private List<AnimationState<T>> currentStates;
    protected T context;
    private Supplier<Iterable<? extends AnimationState<T>>> statesSupplier;
    private final AnimationController animationController;

    public AnimationStateMachine(AnimationController animationController) {
        this.animationController = Objects.requireNonNull(animationController);
    }

    public void update() {
        if (context != null && currentStates != null) {
            currentStates.forEach(state -> state.update(context));
        }
        animationController.update();
    }

    /** 非第一人称更新（本移植没有独立的声音通道，因此与 update 相同） */
    public void visualUpdate() {
        update();
    }

    public void trigger(String condition) {
        if (context == null || currentStates == null) {
            return;
        }
        ListIterator<AnimationState<T>> iterator = currentStates.listIterator();
        while (iterator.hasNext()) {
            AnimationState<T> state = iterator.next();
            AnimationState<T> nextState = state.transition(context, condition);
            if (nextState != null) {
                state.exitAction(context);
                iterator.set(nextState);
                nextState.entryAction(context);
            }
        }
    }

    public void initialize() {
        if (context == null) {
            throw new IllegalStateException("Context must not be null before initialization");
        }
        if (currentStates != null) {
            throw new IllegalStateException("State machine is already initialized");
        }
        this.currentStates = new LinkedList<>();
        Optional.ofNullable(statesSupplier)
                .map(Supplier::get)
                .ifPresent(list -> list.forEach(state -> {
                    currentStates.add(state);
                    state.entryAction(context);
                }));
    }

    public void exit() {
        checkNullPointer();
        currentStates.forEach(state -> state.exitAction(context));
        this.currentStates = null;
    }

    public AnimationController getAnimationController() {
        return animationController;
    }

    public boolean isInitialized() {
        return currentStates != null;
    }

    public T getContext() {
        return context;
    }

    public void processContextIfExist(Consumer<T> consumer) {
        if (context != null) {
            consumer.accept(context);
        }
    }

    public void setContext(T context) {
        AnimationStateMachine<?> stateMachine = context.getStateMachine();
        if (stateMachine != null && stateMachine != this) {
            throw new IllegalStateException("Context is already used");
        }
        if (currentStates != null) {
            throw new IllegalStateException("State machine is already initialized, call exit() first");
        }
        if (this.context != null) {
            this.context.setStateMachine(null);
        }
        context.setStateMachine(this);
        this.context = context;
    }

    public void setStatesSupplier(Supplier<Iterable<? extends AnimationState<T>>> statesSupplier) {
        this.statesSupplier = statesSupplier;
    }

    private void checkNullPointer() {
        if (context == null) {
            throw new IllegalStateException("Context has not been initialized");
        }
        if (currentStates == null) {
            throw new IllegalStateException("State machine has not been initialized");
        }
    }
}
