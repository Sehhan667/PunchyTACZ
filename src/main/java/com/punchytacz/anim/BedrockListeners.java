package com.punchytacz.anim;

import com.punchytacz.bedrock.BedrockModel;
import com.punchytacz.bedrock.BedrockPart;

/** 把动画通道写进 BedrockPart / camera 的监听器（对应 TACZ Model*Listener 与 CameraRotateListener） */
public final class BedrockListeners {
    private BedrockListeners() {
    }

    public static AnimListenerSupplier supplier(BedrockModel model) {
        return (node, type) -> supply(model, node, type);
    }

    public static AnimListener supply(BedrockModel model, String node, AnimChannelType type) {
        if ("camera".equals(node)) {
            return type == AnimChannelType.ROTATION ? new CameraRotate(model) : null;
        }
        BedrockPart part = model.parts.get(node);
        if (part == null) {
            return null;
        }
        return switch (type) {
            case ROTATION -> new Rotate(part);
            case TRANSLATION -> new Translate(part);
            case SCALE -> new Scale(part);
        };
    }

    private static final class Rotate implements AnimListener {
        private final BedrockPart part;

        private Rotate(BedrockPart part) {
            this.part = part;
        }

        @Override
        public void update(float[] values, boolean blend) {
            float x;
            float y;
            float z;
            if (values.length == 4) {
                float[] euler = AnimMath.toEulerAngles(values);
                x = euler[0];
                y = euler[1];
                z = euler[2];
            } else {
                x = values[0];
                y = values[1];
                z = values[2];
            }
            if (blend) {
                float[] current = AnimMath.toEulerAngles(part.additionalQuaternion);
                x += current[0];
                y += current[1];
                z += current[2];
            }
            AnimMath.toQuaternion(x, y, z, part.additionalQuaternion);
        }

        @Override
        public float[] initialValue() {
            return AnimMath.toQuaternion(part.xRot, part.yRot, part.zRot);
        }

        @Override
        public AnimChannelType getType() {
            return AnimChannelType.ROTATION;
        }
    }

    private static final class Translate implements AnimListener {
        private final BedrockPart part;

        private Translate(BedrockPart part) {
            this.part = part;
        }

        @Override
        public void update(float[] values, boolean blend) {
            if (blend) {
                part.offsetX += values[0];
                part.offsetY -= values[1];
                part.offsetZ += values[2];
            } else {
                part.offsetX = values[0];
                part.offsetY = -values[1];
                part.offsetZ = values[2];
            }
        }

        @Override
        public float[] initialValue() {
            return new float[]{part.x / 16f, -part.y / 16f, part.z / 16f};
        }

        @Override
        public AnimChannelType getType() {
            return AnimChannelType.TRANSLATION;
        }
    }

    private static final class Scale implements AnimListener {
        private final BedrockPart part;

        private Scale(BedrockPart part) {
            this.part = part;
        }

        @Override
        public void update(float[] values, boolean blend) {
            if (blend) {
                part.xScale *= values[0];
                part.yScale *= values.length > 1 ? values[1] : values[0];
                part.zScale *= values.length > 2 ? values[2] : values[0];
            } else {
                part.xScale = values[0];
                part.yScale = values.length > 1 ? values[1] : values[0];
                part.zScale = values.length > 2 ? values[2] : values[0];
            }
        }

        @Override
        public float[] initialValue() {
            return new float[]{1f, 1f, 1f};
        }

        @Override
        public AnimChannelType getType() {
            return AnimChannelType.SCALE;
        }
    }

    private static final class CameraRotate implements AnimListener {
        private final BedrockModel model;

        private CameraRotate(BedrockModel model) {
            this.model = model;
        }

        @Override
        public void update(float[] values, boolean blend) {
            float[] euler = values.length == 4 ? AnimMath.toEulerAngles(values) : values;
            float x = euler[0];
            float y = euler[1];
            float z = -euler[2];
            if (blend) {
                float[] current = AnimMath.toEulerAngles(model.cameraRotation);
                x += current[0];
                y += current[1];
                z += current[2];
            }
            AnimMath.toQuaternion(x, y, z, model.cameraRotation);
        }

        @Override
        public float[] initialValue() {
            return new float[]{0f, 0f, 0f, 1f};
        }

        @Override
        public AnimChannelType getType() {
            return AnimChannelType.ROTATION;
        }
    }
}
