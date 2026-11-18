package com.punchytacz.bedrock;

import net.minecraft.core.Direction;

/** 取自 TACZ（GPL-3.0）pojo 的六面 UV 集合 */
public class FaceUVsItem {
    private FaceItem down;
    private FaceItem east;
    private FaceItem north;
    private FaceItem south;
    private FaceItem up;
    private FaceItem west;

    public void set(String face, FaceItem item) {
        switch (face) {
            case "down" -> down = item;
            case "east" -> east = item;
            case "north" -> north = item;
            case "south" -> south = item;
            case "up" -> up = item;
            case "west" -> west = item;
            default -> {
            }
        }
    }

    public FaceItem getFace(Direction direction) {
        FaceItem face = switch (direction) {
            case EAST -> east;
            case WEST -> west;
            case NORTH -> north;
            case SOUTH -> south;
            case UP -> up;
            default -> down;
        };
        return face == null ? FaceItem.EMPTY : face;
    }
}
