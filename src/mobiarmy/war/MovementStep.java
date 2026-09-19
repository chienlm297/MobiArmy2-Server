package mobiarmy.war;

/** Pure walking rule shared by prediction and actual movement. */
public final class MovementStep {
    public record Result(int x, int y, boolean moved) {}
    public static Result next(MapData map, int x, int y, boolean right, int speed, boolean fly) {
        int nx = x + (right ? speed : -speed);
        if (nx < 0 || nx >= map.width || map.isCollisionMap(nx, y - 5))
            return new Result(x, y, false);
        for (int i = 4; i >= 0; i--)
            if (map.isCollisionMap(nx, y - i)) return new Result(nx, y - i, true);
        int ny = y;
        if (!fly) while (ny < map.height + 200 && !map.isCollisionMap(nx, ny)) ny++;
        return new Result(nx, ny, true);
    }
    private MovementStep() {}
}
