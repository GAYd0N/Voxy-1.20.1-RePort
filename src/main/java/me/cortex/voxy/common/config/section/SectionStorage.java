package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

import java.util.function.LongConsumer;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);

    public abstract void iterateStoredSectionPositions(LongConsumer consumer);

    @Override
    public void iteratePositions(int level, LongConsumer callback) {
        this.iterateStoredSectionPositions(callback);
    }
}
