package com.punchytacz.anim;

import org.joml.Quaternionf;

/** TACZ MathUtil 中动画运行时需要的数学子集（旋转顺序与符号必须保持一致） */
public final class AnimMath {
    private AnimMath() {
    }

    /** 按 z(roll) -> y(yaw) -> x(pitch) 顺序求四元数，参数为弧度 */
    public static float[] toQuaternion(float pitch, float yaw, float roll) {
        double cy = Math.cos(roll * 0.5);
        double sy = Math.sin(roll * 0.5);
        double cp = Math.cos(yaw * 0.5);
        double sp = Math.sin(yaw * 0.5);
        double cr = Math.cos(pitch * 0.5);
        double sr = Math.sin(pitch * 0.5);
        return new float[]{
                (float) (cy * cp * sr - sy * sp * cr),
                (float) (sy * cp * sr + cy * sp * cr),
                (float) (sy * cp * cr - cy * sp * sr),
                (float) (cy * cp * cr + sy * sp * sr)
        };
    }

    public static void toQuaternion(float pitch, float yaw, float roll, Quaternionf dest) {
        double cy = Math.cos(roll * 0.5);
        double sy = Math.sin(roll * 0.5);
        double cp = Math.cos(yaw * 0.5);
        double sp = Math.sin(yaw * 0.5);
        double cr = Math.cos(pitch * 0.5);
        double sr = Math.sin(pitch * 0.5);
        dest.set(
                (float) (cy * cp * sr - sy * sp * cr),
                (float) (sy * cp * sr + cy * sp * cr),
                (float) (sy * cp * cr - cy * sp * sr),
                (float) (cy * cp * cr + sy * sp * sr));
    }

    public static Quaternionf toQuaternion(float[] q) {
        return new Quaternionf(q[0], q[1], q[2], q[3]);
    }

    public static float[] toEulerAngles(Quaternionf q) {
        float[] angles = new float[3];
        double sinrCosp = 2 * (q.w() * q.x() + q.y() * q.z());
        double cosrCosp = 1 - 2 * (q.x() * q.x() + q.y() * q.y());
        angles[0] = (float) Math.atan2(sinrCosp, cosrCosp);
        double sinp = 2 * (q.w() * q.y() - q.x() * q.z());
        if (Math.abs(sinp) >= 1) {
            angles[1] = (float) Math.copySign(Math.PI / 2, sinp);
        } else {
            angles[1] = (float) Math.asin(sinp);
        }
        double sinyCosp = 2 * (q.w() * q.z() + q.y() * q.x());
        double cosyCosp = 1 - 2 * (q.y() * q.y() + q.z() * q.z());
        angles[2] = (float) Math.atan2(sinyCosp, cosyCosp);
        return angles;
    }

    public static float[] toEulerAngles(float[] q) {
        float[] angles = new float[3];
        double sinrCosp = 2 * (q[3] * q[0] + q[1] * q[2]);
        double cosrCosp = 1 - 2 * (q[0] * q[0] + q[1] * q[1]);
        angles[0] = (float) Math.atan2(sinrCosp, cosrCosp);
        double sinp = 2 * (q[3] * q[1] - q[2] * q[0]);
        if (Math.abs(sinp) >= 1) {
            angles[1] = (float) Math.copySign(Math.PI / 2, sinp);
        } else {
            angles[1] = (float) Math.asin(sinp);
        }
        double sinyCosp = 2 * (q[3] * q[2] + q[1] * q[0]);
        double cosyCosp = 1 - 2 * (q[1] * q[1] + q[2] * q[2]);
        angles[2] = (float) Math.atan2(sinyCosp, cosyCosp);
        return angles;
    }

    public static float[] inverseQuaternion(float[] quaternion) {
        float[] result = new float[4];
        result[0] = -quaternion[0];
        result[1] = -quaternion[1];
        result[2] = -quaternion[2];
        result[3] = quaternion[3];
        float m2 = quaternion[0] * quaternion[0] + quaternion[1] * quaternion[1]
                + quaternion[2] * quaternion[2] + quaternion[3] * quaternion[3];
        result[0] /= m2;
        result[1] /= m2;
        result[2] /= m2;
        result[3] /= m2;
        return result;
    }

    public static float[] mulQuaternion(float[] q1, float[] q2) {
        return new float[]{
                Math.fma(q1[3], q2[0], Math.fma(q1[0], q2[3], Math.fma(q1[1], q2[2], -q1[2] * q2[1]))),
                Math.fma(q1[3], q2[1], Math.fma(-q1[0], q2[2], Math.fma(q1[1], q2[3], q1[2] * q2[0]))),
                Math.fma(q1[3], q2[2], Math.fma(q1[0], q2[1], Math.fma(-q1[1], q2[0], q1[2] * q2[3]))),
                Math.fma(q1[3], q2[3], Math.fma(-q1[0], q2[0], Math.fma(-q1[1], q2[1], -q1[2] * q2[2])))
        };
    }

    public static float[] slerp(float[] from, float[] to, float alpha) {
        float ax = from[0];
        float ay = from[1];
        float az = from[2];
        float aw = from[3];
        float bx = to[0];
        float by = to[1];
        float bz = to[2];
        float bw = to[3];

        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0) {
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            dot = -dot;
        }
        float s0;
        float s1;
        if ((1.0 - dot) > 1e-6f) {
            float omega = (float) Math.acos(dot);
            float invSinOmega = 1.0f / (float) Math.sin(omega);
            s0 = (float) Math.sin((1.0 - alpha) * omega) * invSinOmega;
            s1 = (float) Math.sin(alpha * omega) * invSinOmega;
        } else {
            s0 = 1.0f - alpha;
            s1 = alpha;
        }
        return new float[]{
                s0 * ax + s1 * bx,
                s0 * ay + s1 * by,
                s0 * az + s1 * bz,
                s0 * aw + s1 * bw
        };
    }

    /** BlockBench 使用的三次样条（tension=0.5） */
    public static float splineCurve(float[] y, float tension, float alpha) {
        float v0 = (y[2] - y[0]) * 0.5f;
        float v1 = (y[3] - y[1]) * 0.5f;
        float t2 = alpha * alpha;
        float t3 = alpha * t2;
        float h1 = 2f * t3 - 3f * t2 + 1f;
        float h2 = -2f * t3 + 3f * t2;
        float h3 = t3 - 2f * t2 + alpha;
        float h4 = t3 - t2;
        return h1 * y[1] + h2 * y[2] + h3 * v0 + h4 * v1;
    }

    /** 按欧拉角缩放旋转量（用于镜头动画的 multiplier） */
    public static Quaternionf multiplyQuaternion(Quaternionf quaternion, float multiplier) {
        float[] euler = toEulerAngles(quaternion);
        float[] q = toQuaternion(euler[0] * multiplier, euler[1] * multiplier, euler[2] * multiplier);
        return new Quaternionf(q[0], q[1], q[2], q[3]);
    }
}
