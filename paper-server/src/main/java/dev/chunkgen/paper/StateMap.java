package dev.chunkgen.paper;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

/**
 * Maps SteelMC's global block state ids onto this server's {@link BlockState}s.
 *
 * <p>Steel numbers block states itself, so the table is built once, lazily, by
 * asking Rust to describe every id and resolving that description against
 * {@link BuiltInRegistries#BLOCK} and the block's state definition. The result
 * is an {@code int[]} of {@link Block#BLOCK_STATE_REGISTRY} ids, which is what
 * the chunk writer stores, so the hot path is a single array index.</p>
 *
 * <p>An id that cannot be resolved is a version mismatch, not a cosmetic
 * problem: substituting stone would silently corrupt terrain. The table
 * therefore collects every failure and throws, naming all of them, so the
 * mismatch shows up at world load instead of in a player's screenshot.</p>
 */
final class StateMap {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile StateMap instance;

    private final int[] vanillaIds;
    private final int unmappedCount;

    private StateMap(final int[] vanillaIds, final int unmappedCount) {
        this.vanillaIds = vanillaIds;
        this.unmappedCount = unmappedCount;
    }

    /** The process-wide table, built on first use. */
    static StateMap get() {
        StateMap local = instance;
        if (local != null) {
            return local;
        }
        synchronized (StateMap.class) {
            if (instance == null) {
                instance = load();
            }
            return instance;
        }
    }

    private static StateMap load() {
        long started = System.nanoTime();
        int count = RustNative.stateCount();
        int[] ids = new int[count];
        List<String> failures = new ArrayList<>();
        // Descriptions repeat heavily (a block's 60 states share its key), so
        // memoise the resolved state per description string.
        Map<String, Integer> resolved = new HashMap<>(count * 2);

        for (int rustId = 0; rustId < count; rustId++) {
            String[] description = RustNative.describeState(rustId);
            if (description == null) {
                failures.add("state id " + rustId + ": Rust could not describe it");
                ids[rustId] = -1;
                continue;
            }
            String key = description[0];
            String properties = description[1];
            String text = properties.isEmpty() ? key : key + '[' + properties.replace('\n', ',') + ']';

            Integer cached = resolved.get(text);
            if (cached == null) {
                cached = resolve(key, properties, text, failures, rustId);
                resolved.put(text, cached);
            }
            ids[rustId] = cached;
        }

        if (!failures.isEmpty()) {
            String message = "Rust block state table does not match this server: " + failures.size()
                    + " of " + count + " states could not be resolved";
            // Naming all of them matters: the first few usually share a cause,
            // but a list is the only way to see the full extent of a mismatch.
            for (String failure : failures) {
                LOGGER.error("chunkgen: {}", failure);
            }
            throw new IllegalStateException(message + " (see the log for the full list)");
        }

        double millis = (System.nanoTime() - started) / 1.0e6;
        LOGGER.info("chunkgen: mapped {} Rust block states onto vanilla in {} ms ({} distinct descriptions)",
                count, String.format(java.util.Locale.ROOT, "%.0f", millis), resolved.size());
        return new StateMap(ids, 0);
    }

    private static int resolve(final String key, final String properties, final String text,
            final List<String> failures, final int rustId) {
        Identifier id;
        try {
            id = Identifier.parse(key);
        } catch (final RuntimeException error) {
            failures.add("state id " + rustId + " (" + text + "): bad identifier");
            return -1;
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            failures.add("state id " + rustId + " (" + text + "): no such block");
            return -1;
        }

        BlockState state = block.get().defaultBlockState();
        if (!properties.isEmpty()) {
            for (String pair : properties.split("\n")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    failures.add("state id " + rustId + " (" + text + "): malformed property " + pair);
                    return -1;
                }
                String name = pair.substring(0, equals);
                String value = pair.substring(equals + 1);
                Property<?> property = find(state, name);
                if (property == null) {
                    failures.add("state id " + rustId + " (" + text + "): block has no property "
                            + name);
                    return -1;
                }
                state = apply(state, property, value);
                if (state == null) {
                    failures.add("state id " + rustId + " (" + text + "): bad value " + value
                            + " for " + name);
                    return -1;
                }
            }
        }
        return Block.BLOCK_STATE_REGISTRY.getId(state);
    }

    private static Property<?> find(final BlockState state, final String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    private static <T extends Comparable<T>> BlockState apply(final BlockState state,
            final Property<T> property, final String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(null);
    }

    /** The vanilla registry id for a Rust state id. */
    int vanillaId(final int rustStateId) {
        return rustStateId >= 0 && rustStateId < vanillaIds.length ? vanillaIds[rustStateId] : -1;
    }

    /** How many ids had no vanilla equivalent; always zero once loading succeeded. */
    int unmappedCount() {
        return unmappedCount;
    }

    int size() {
        return vanillaIds.length;
    }
}
