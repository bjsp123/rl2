package com.bjsp123.rl2.world.render;

import com.bjsp123.rl2.model.Item;
import com.bjsp123.rl2.model.Level;
import com.bjsp123.rl2.model.Mob;

import java.util.ArrayList;
import java.util.List;

/** Cell-index preparation for the level renderer's south-to-north draw walk. */
final class LevelRenderIndexes {
    private LevelRenderIndexes() {}

    static final class CellBuckets<T> {
        private final int width;
        private final int height;
        private final List<T>[] cells;

        @SuppressWarnings("unchecked")
        CellBuckets(int width, int height) {
            this.width = width;
            this.height = height;
            this.cells = (List<T>[]) new List[width * height];
        }

        void add(int x, int y, T value) {
            if (x < 0 || y < 0 || x >= width || y >= height) return;
            int idx = y * width + x;
            List<T> list = cells[idx];
            if (list == null) cells[idx] = list = new ArrayList<>(1);
            list.add(value);
        }

        List<T> at(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) return null;
            return cells[y * width + x];
        }
    }

    static CellBuckets<Item> itemsByCell(Level level) {
        CellBuckets<Item> out = new CellBuckets<>(level.width, level.height);
        for (Item it : level.items) {
            if (it.location == null) continue;
            out.add(it.location.tileX(), it.location.tileY(), it);
        }
        return out;
    }

    static CellBuckets<Mob> mobsByCell(Level level) {
        CellBuckets<Mob> out = new CellBuckets<>(level.width, level.height);
        for (Mob mob : level.mobs) {
            if (mob.position == null) continue;
            out.add(mob.position.tileX(), mob.position.tileY(), mob);
        }
        return out;
    }

    static CellBuckets<Effect> effectsByCell(Level level, EffectStage stage) {
        CellBuckets<Effect> out = new CellBuckets<>(level.width, level.height);
        for (Effect e : stage.active) {
            if (e.location == null) continue;
            if (e.type == Effect.EffectType.DUST_CLOUD) continue;
            int ax = e.location.tileX();
            int ay = e.location.tileY();
            if (e.endLocation != null && e.endLocation.tileY() < ay) {
                ax = e.endLocation.tileX();
                ay = e.endLocation.tileY();
            }
            out.add(ax, ay, e);
        }
        return out;
    }
}
