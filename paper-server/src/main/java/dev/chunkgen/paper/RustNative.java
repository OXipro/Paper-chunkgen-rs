package dev.chunkgen.paper;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;

/**
 * {@code java.lang.foreign} bindings for {@code libchunkgen}.
 *
 * <p>This is the fork's copy of the plugin's {@code dev.chunkgen.ffm.Native},
 * trimmed to what the generator needs and extended with the stage-aware entry
 * point. The library is loaded once per JVM; every handle is static and
 * thread-safe, and chunk buffers stay owned by Rust until
 * {@link #freeChunk(MemorySegment)}.</p>
 */
final class RustNative {

    /** Mirror of Rust's {@code CgChunkBuffer}. */
    private static final StructLayout CHUNK_BUFFER = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("chunk_x"),
            ValueLayout.JAVA_INT.withName("chunk_z"),
            ValueLayout.JAVA_INT.withName("min_y"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.ADDRESS.withName("blocks"),
            ValueLayout.JAVA_LONG.withName("blocks_len"),
            ValueLayout.ADDRESS.withName("biomes"),
            ValueLayout.JAVA_LONG.withName("biomes_len"),
            ValueLayout.ADDRESS.withName("surface_heights"),
            ValueLayout.JAVA_LONG.withName("surface_heights_len"),
            ValueLayout.ADDRESS.withName("sky_light"),
            ValueLayout.JAVA_LONG.withName("sky_light_len"),
            ValueLayout.ADDRESS.withName("block_light"),
            ValueLayout.JAVA_LONG.withName("block_light_len"),
            ValueLayout.ADDRESS.withName("structures"),
            ValueLayout.ADDRESS.withName("structure_references"),
            ValueLayout.JAVA_LONG.withName("structure_references_len"),
            ValueLayout.ADDRESS.withName("owner"));

    /** Mirror of Rust's {@code CgStateDesc}. */
    private static final StructLayout STATE_DESC = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("key"),
            ValueLayout.ADDRESS.withName("properties"));

    /** Mirror of Rust's {@code CgString}. */
    private static final StructLayout STRING_WRAPPER = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("data"),
            ValueLayout.JAVA_LONG.withName("len"),
            ValueLayout.ADDRESS.withName("owner"));

    private static final VarHandle CHUNK_X = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("chunk_x"));
    private static final VarHandle CHUNK_Z = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("chunk_z"));
    private static final VarHandle MIN_Y = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("min_y"));
    private static final VarHandle HEIGHT = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("height"));
    private static final VarHandle BLOCKS = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("blocks"));
    private static final VarHandle BLOCKS_LEN = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("blocks_len"));
    private static final VarHandle BIOMES = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("biomes"));
    private static final VarHandle BIOMES_LEN = CHUNK_BUFFER.varHandle(
            MemoryLayout.PathElement.groupElement("biomes_len"));
    private static final VarHandle DESC_KEY = STATE_DESC.varHandle(
            MemoryLayout.PathElement.groupElement("key"));
    private static final VarHandle DESC_PROPERTIES = STATE_DESC.varHandle(
            MemoryLayout.PathElement.groupElement("properties"));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB;

    private static final MethodHandle GENERATOR_NEW;
    private static final MethodHandle GENERATOR_FREE;
    private static final MethodHandle GENERATE_EX;
    private static final MethodHandle CHUNK_FREE;
    private static final MethodHandle STATE_COUNT;
    private static final MethodHandle STATE_DESCRIBE;
    private static final MethodHandle STRING_FREE;
    private static final MethodHandle BIOME_COUNT;
    private static final MethodHandle BIOME_NAME;
    private static final MethodHandle STRING_VALUE_FREE;
    private static final MethodHandle VERSION;

    static {
        String path = System.getProperty("chunkgen.library", "libchunkgen.so");
        if (path.isBlank()) {
            path = "libchunkgen.so";
        }
        LIB = SymbolLookup.libraryLookup(Path.of(path), Arena.global());
        GENERATOR_NEW = LINKER.downcallHandle(down("cg_generator_new"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        GENERATOR_FREE = LINKER.downcallHandle(down("cg_generator_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        GENERATE_EX = LINKER.downcallHandle(down("cg_generate_ex"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT));
        CHUNK_FREE = LINKER.downcallHandle(down("cg_chunk_buffer_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        STATE_COUNT = LINKER.downcallHandle(down("cg_state_count"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        STATE_DESCRIBE = LINKER.downcallHandle(down("cg_state_describe"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_SHORT,
                        ValueLayout.ADDRESS));
        STRING_FREE = LINKER.downcallHandle(down("cg_string_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        BIOME_COUNT = LINKER.downcallHandle(down("cg_biome_count"),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        BIOME_NAME = LINKER.downcallHandle(down("cg_biome_name"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT));
        STRING_VALUE_FREE = LINKER.downcallHandle(down("cg_string_value_free"),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        VERSION = LINKER.downcallHandle(down("cg_version"),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
    }

    private static MemorySegment down(final String symbol) {
        return LIB.find(symbol).orElseThrow(() ->
                new UnsatisfiedLinkError("libchunkgen is missing " + symbol));
    }

    private RustNative() {
    }

    /** Receives one raw chunk. The segments are only valid inside the call. */
    interface ChunkSink {
        void accept(int minY, int height, MemorySegment blocks, MemorySegment biomes);
    }

    static String version() {
        try {
            MemorySegment wrapper = (MemorySegment) VERSION.invokeExact();
            String text = readWrapper(wrapper);
            STRING_VALUE_FREE.invokeExact(wrapper);
            return text;
        } catch (final Throwable error) {
            throw new IllegalStateException("cg_version failed", error);
        }
    }

    private static String readWrapper(final MemorySegment wrapper) {
        if (wrapper.address() == 0L) {
            return "";
        }
        MemorySegment view = wrapper.reinterpret(STRING_WRAPPER.byteSize());
        MemorySegment data = (MemorySegment) STRING_WRAPPER.varHandle(
                MemoryLayout.PathElement.groupElement("data")).get(view, 0L);
        long len = (long) STRING_WRAPPER.varHandle(
                MemoryLayout.PathElement.groupElement("len")).get(view, 0L);
        if (len == 0L || data.address() == 0L) {
            return "";
        }
        return data.reinterpret(len + 1L).getString(0L);
    }

    static int stateCount() {
        try {
            return (int) (long) STATE_COUNT.invokeExact();
        } catch (final Throwable error) {
            throw new IllegalStateException("cg_state_count failed", error);
        }
    }

    /**
     * Describes one Rust block state.
     *
     * @return {@code [key, properties]} where properties is newline-separated
     *         {@code name=value} pairs, or {@code null} if the id is unknown
     */
    static String[] describeState(final int stateId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(STATE_DESC);
            int status = (int) STATE_DESCRIBE.invokeExact((short) stateId, out);
            if (status != 0) {
                return null;
            }
            MemorySegment key = (MemorySegment) DESC_KEY.get(out, 0L);
            MemorySegment properties = (MemorySegment) DESC_PROPERTIES.get(out, 0L);
            String keyText = readCString(key);
            String propertiesText = readCString(properties);
            STRING_FREE.invokeExact(key);
            STRING_FREE.invokeExact(properties);
            return new String[] {keyText, propertiesText};
        } catch (final Throwable error) {
            throw new IllegalStateException("cg_state_describe failed", error);
        }
    }

    private static String readCString(final MemorySegment pointer) {
        if (pointer == null || pointer.address() == 0L) {
            return "";
        }
        return pointer.reinterpret(Long.MAX_VALUE).getString(0L);
    }

    static int biomeCount() {
        try {
            return (int) (long) BIOME_COUNT.invokeExact();
        } catch (final Throwable error) {
            throw new IllegalStateException("cg_biome_count failed", error);
        }
    }

    static String biomeName(final int biomeId) {
        try {
            MemorySegment wrapper = (MemorySegment) BIOME_NAME.invokeExact((short) biomeId);
            String text = readWrapper(wrapper);
            STRING_VALUE_FREE.invokeExact(wrapper);
            return text;
        } catch (final Throwable error) {
            throw new IllegalStateException("cg_biome_name failed", error);
        }
    }

    /** A Rust generator handle; close it to release the Rust world. */
    static final class Generator implements AutoCloseable {

        private final MemorySegment handle;
        private final Arena arena;
        /** Seed this handle was built for; the level seed, kept for checks. */
        final long seed;
        /** Dimension this handle was built for. */
        final String dimension;

        private Generator(final MemorySegment handle, final Arena arena, final long seed,
                final String dimension) {
            this.handle = handle;
            this.arena = arena;
            this.seed = seed;
            this.dimension = dimension;
        }

        static Generator create(final long seed, final String dimension) {
            Arena arena = Arena.ofShared();
            try {
                MemorySegment name = arena.allocateFrom(dimension);
                MemorySegment handle = (MemorySegment) GENERATOR_NEW.invokeExact(seed, name);
                if (handle.address() == 0L) {
                    arena.close();
                    throw new IllegalArgumentException("unknown dimension: " + dimension);
                }
                return new Generator(handle, arena, seed, dimension);
            } catch (final IllegalStateException | IllegalArgumentException error) {
                arena.close();
                throw error;
            } catch (final Throwable error) {
                arena.close();
                throw new IllegalStateException("cg_generator_new failed", error);
            }
        }

        /**
         * Generates one chunk up to {@code stage} and hands its buffers to
         * {@code sink}. Buffers are only valid inside the call.
         */
        void generate(final int chunkX, final int chunkZ, final String stage,
                final int margin, final ChunkSink sink) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment stageName = arena.allocateFrom(stage);
                MemorySegment raw = (MemorySegment) GENERATE_EX.invokeExact(
                        handle, chunkX, chunkZ, stageName, margin);
                if (raw.address() == 0L) {
                    throw new IllegalStateException(
                            "cg_generate_ex returned null for (" + chunkX + "," + chunkZ
                                    + ") stage " + stage);
                }
                MemorySegment view = raw.reinterpret(CHUNK_BUFFER.byteSize());
                try {
                    MemorySegment blocks = ((MemorySegment) BLOCKS.get(view, 0L)).reinterpret(
                            (long) BLOCKS_LEN.get(view, 0L) * 2L);
                    MemorySegment biomes = ((MemorySegment) BIOMES.get(view, 0L)).reinterpret(
                            (long) BIOMES_LEN.get(view, 0L) * 2L);
                    sink.accept((int) MIN_Y.get(view, 0L), (int) HEIGHT.get(view, 0L),
                            blocks, biomes);
                } finally {
                    CHUNK_FREE.invokeExact(raw);
                }
            } catch (final RuntimeException error) {
                throw error;
            } catch (final Throwable error) {
                throw new IllegalStateException("cg_generate_ex failed", error);
            }
        }

        @Override
        public void close() {
            try {
                GENERATOR_FREE.invokeExact(handle);
            } catch (final Throwable error) {
                throw new IllegalStateException("cg_generator_free failed", error);
            } finally {
                arena.close();
            }
        }
    }

    /** Size of the buffer struct, for diagnostics. */
    static long bufferSize() {
        return CHUNK_BUFFER.byteSize();
    }
}
