package dev.chunkgen.paper;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A chunk generator whose <em>terrain</em> is produced by the Rust library.
 *
 * <h2>What Rust replaces, and what it does not</h2>
 *
 * <p>Vanilla's {@code ChunkStatus.TERRAIN} step is three things: noise fill,
 * surface rules and carvers. Those three are what Rust computes, from SteelMC's
 * vanilla-faithful worldgen, and this class copies the resulting blocks into the
 * chunk's sections. Everything around the terrain stays in Java and stays
 * vanilla:</p>
 *
 * <ul>
 *   <li><b>Biomes</b> come from the real {@code MultiNoiseBiomeSource} through
 *       {@link ChunkGenerator#createBiomes}, so biome placement is bit-identical
 *       by construction and features are seeded from the same palette vanilla
 *       would use.</li>
 *   <li><b>Structure starts and pieces</b> use {@link ChunkGenerator#createStructures}
 *       / {@code createReferences}, which this class does not override.</li>
 *   <li><b>Features</b> use {@link ChunkGenerator#addVanillaDecorations}, also
 *       inherited.</li>
 *   <li><b>Lighting and mob spawning</b> run in Paper's own stages.</li>
 * </ul>
 *
 * <p>The consequence is that Rust never has to reproduce feature RNG order,
 * which is the one part of vanilla generation that is order-dependent and the
 * reason a purely Rust pipeline needs a 2-chunk margin to converge.</p>
 *
 * <h2>Why {@code NoiseBasedChunkGenerator} is used as a delegate</h2>
 *
 * <p>A few abstract methods describe the density field rather than the chunk:
 * {@code getBaseHeight}, {@code getBaseColumn} and {@code getOrigin}. Structure
 * placement (mineshafts, ruined portals) and the spawn search call them before
 * any chunk exists. Rather than reimplement noise sampling, this class holds a
 * {@link NoiseBasedChunkGenerator} built from the same biome source and
 * settings and forwards those calls to it, so they return exactly what vanilla
 * would.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>Paper generates chunks on several threads and calls {@link #buildTerrain}
 * concurrently. The Rust {@code Generator} supports concurrent
 * {@code generate} calls (it owns a SteelMC world whose chunk scheduler is
 * internally synchronised), so one instance is shared and no Java-side lock is
 * taken on the hot path. The only mutable Java state is the lazily created
 * generator handle, published through an {@link AtomicReference}.</p>
 */
public final class RustChunkGenerator extends ChunkGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stage name handed to the Rust entry point: terrain only. */
    private static final String STAGE = "terrain";

    public static final MapCodec<RustChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.settings),
                    com.mojang.serialization.Codec.STRING.optionalFieldOf("dimension", "minecraft:overworld")
                            .forGetter(g -> g.dimension))
                    .apply(instance, instance.stable(RustChunkGenerator::new)));

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** How many chunks out of the neighbours Rust must materialise first. */
    private final int margin;

    /** Whether fluid blocks are queued for a post-generation fluid tick. */
    private final boolean scheduleFluidTicks;

    private final String dimension;
    private final Holder<NoiseGeneratorSettings> settings;

    /** Vanilla generator for the noise-only queries listed in the class docs. */
    private final NoiseBasedChunkGenerator delegate;

    /** Rust handle, created on the first chunk because only then is the seed known. */
    private final AtomicReference<RustNative.Generator> generator = new AtomicReference<>();

    private final AtomicLong chunkCount = new AtomicLong();
    private final AtomicLong rustNanos = new AtomicLong();
    private final AtomicLong writeNanos = new AtomicLong();

    // Concurrency accounting: how many buildTerrain calls overlap, and which
    // threads they run on. This is what proves the fork really generates in
    // parallel rather than the number the benchmark claims.
    private final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RustChunkGenerator(final BiomeSource biomeSource,
            final Holder<NoiseGeneratorSettings> settings) {
        this(biomeSource, settings, "minecraft:overworld");
    }

    public RustChunkGenerator(final BiomeSource biomeSource,
            final Holder<NoiseGeneratorSettings> settings,
            final String dimension) {
        super(biomeSource);
        this.settings = settings;
        this.dimension = dimension;
        this.delegate = new NoiseBasedChunkGenerator(biomeSource, settings);
        INSTANCES.add(this);
        // Default 0: the margin exists to make *feature* placement converge, and
        // features are added by Java here, so paying for the ring only slows the
        // terrain fill down. Measured identical fidelity at 0 and 2 in-server
        // (95.658% both) with 73.5 vs 44.8 chunks/s, so 0 is the default.
        this.margin = Integer.getInteger("chunkgen.margin", 0);
        this.scheduleFluidTicks = Boolean.parseBoolean(
                System.getProperty("chunkgen.fluidPostProcessing", "true"));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ------------------------------------------------------------------ terrain

    @Override
    public java.util.concurrent.CompletableFuture<ChunkAccess> buildTerrain(
            final ChunkAccess chunk,
            final Blender blender,
            final RandomState randomState,
            final StructureManager structureManager,
            final BiomeManager biomeManager,
            final @Nullable WorldGenRegion carverBiomeRegion,
            final Set<Holder<Biome>> possibleBiomes) {
        return java.util.concurrent.CompletableFuture.supplyAsync(
                () -> this.fillFromRust(chunk, randomState), Runnable::run);
    }

    private ChunkAccess fillFromRust(final ChunkAccess chunk, final RandomState randomState) {
        int concurrent = this.active.incrementAndGet();
        this.peak.accumulateAndGet(concurrent, Math::max);
        this.threads.add(Thread.currentThread().getName());
        try {
            return this.fillFromRustLocked(chunk, randomState);
        } finally {
            this.active.decrementAndGet();
        }
    }

    private ChunkAccess fillFromRustLocked(final ChunkAccess chunk, final RandomState randomState) {
        StateMap states = StateMap.get();
        RustNative.Generator rust = this.generatorFor(randomState.seed());
        ChunkPos pos = chunk.getPos();

        // Sections must be acquired before writing, exactly as vanilla's
        // doFill path does; Moonrise guards palette access with this.
        int topSection = chunk.getSectionsCount() - 1;
        for (int index = 0; index <= topSection; index++) {
            chunk.getSection(index).acquire();
        }
        try {
            ProfilerFiller profiler = Profiler.get();
            try (Zone zone = profiler.zone("chunkgen:rust")) {
                final long[] rustTime = new long[1];
                long started = System.nanoTime();
                rust.generate(pos.x(), pos.z(), STAGE, this.margin,
                        (minY, height, blocks, biomes) -> {
                            rustTime[0] = System.nanoTime() - started;
                            this.writeChunk(chunk, states, minY, height, blocks);
                        });
                this.rustNanos.addAndGet(rustTime[0]);
                this.writeNanos.addAndGet(System.nanoTime() - started - rustTime[0]);
                this.chunkCount.incrementAndGet();
            }
        } finally {
            for (int index = 0; index <= topSection; index++) {
                chunk.getSection(index).release();
            }
        }
        return chunk;
    }

    private void writeChunk(final ChunkAccess chunk, final StateMap states, final int minY,
            final int height, final java.lang.foreign.MemorySegment blocks) {
        int expected = 16 * 16 * height;
        long actual = blocks.byteSize() / 2L;
        if (actual != expected) {
            throw new IllegalStateException("Rust returned " + actual + " blocks for a "
                    + height + "-block-tall chunk, expected " + expected);
        }

        char[] rustStates = new char[expected];
        java.lang.foreign.MemorySegment.copy(blocks,
                java.lang.foreign.ValueLayout.JAVA_CHAR_UNALIGNED, 0L, rustStates, 0, expected);

        // The chunk's section array is indexed from getMinSectionY(), which is
        // the dimension's minimum; Rust's arrays start at the dimension's min_y,
        // so the two agree for a matching dimension type. Verify instead of
        // assuming, since a mismatch would shear the terrain vertically.
        int sectionBase = chunk.getMinSectionY() * 16;
        if (sectionBase != minY) {
            throw new IllegalStateException("Rust min_y " + minY
                    + " does not match this level's sections starting at " + sectionBase);
        }

        BlockPos.MutableBlockPos mutable = this.scheduleFluidTicks ? new BlockPos.MutableBlockPos() : null;
        int blockX = chunk.getPos().getMinBlockX();
        int blockZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection section = null;
        int sectionIndex = Integer.MIN_VALUE;

        for (int y = 0; y < height; y++) {
            int wantedIndex = y >> 4;
            if (wantedIndex != sectionIndex) {
                sectionIndex = wantedIndex;
                section = chunk.getSection(wantedIndex);
            }
            int sectionY = y & 15;
            int rowBase = y * 256;
            for (int z = 0; z < 16; z++) {
                int columnBase = rowBase + z * 16;
                for (int x = 0; x < 16; x++) {
                    int vanillaId = states.vanillaId(rustStates[columnBase + x]);
                    if (vanillaId <= 0) {
                        // 0 is air's registry id, which is exactly what we want
                        // for a Rust air state; -1 means the table is broken,
                        // which the table loading already refuses to allow.
                        continue;
                    }
                    BlockState state = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY
                            .byId(vanillaId);
                    if (state == null || state == AIR) {
                        continue;
                    }
                    section.setBlockState(x, sectionY, z, state, false);
                    if (mutable != null && !state.getFluidState().isEmpty()) {
                        mutable.set(blockX + x, minY + y, blockZ + z);
                        chunk.markPosForPostProcessing(mutable);
                    }
                }
            }
        }

        // Vanilla's doFill feeds the two worldgen heightmaps as blocks land;
        // ChunkStatusTasks then primes the final heightmaps from the sections,
        // so only these two need to exist here.
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
    }

    private RustNative.Generator generatorFor(final long seed) {
        RustNative.Generator existing = this.generator.get();
        if (existing != null) {
            return existing;
        }
        synchronized (this.generator) {
            RustNative.Generator again = this.generator.get();
            if (again != null) {
                if (again.seed != seed) {
                    throw new IllegalStateException("Rust generator was built for seed "
                            + again.seed + " but the level seed is " + seed);
                }
                return again;
            }
            long started = System.nanoTime();
            RustNative.Generator created = RustNative.Generator.create(seed, this.dimension);
            this.generator.set(created);
            LOGGER.info("chunkgen: Rust generator ready in {} ms (seed {}, dimension {}, stage {}, margin {})",
                    String.format(java.util.Locale.ROOT, "%.0f", (System.nanoTime() - started) / 1.0e6),
                    seed, this.dimension, STAGE, this.margin);
            return created;
        }
    }

    // ------------------------------------------- noise-only queries, delegated

    @Override
    public int getBaseHeight(final int x, final int z, final Heightmap.Types type,
            final LevelHeightAccessor heightAccessor, final RandomState randomState) {
        return this.delegate.getBaseHeight(x, z, type, heightAccessor, randomState);
    }

    @Override
    public NoiseColumn getBaseColumn(final int x, final int z,
            final LevelHeightAccessor heightAccessor, final RandomState randomState) {
        return this.delegate.getBaseColumn(x, z, heightAccessor, randomState);
    }

    @Override
    public ChunkPos getOrigin(final RandomState randomState) {
        return this.delegate.getOrigin(randomState);
    }

    @Override
    public int getGenDepth() {
        return this.delegate.getGenDepth();
    }

    @Override
    public int getSeaLevel() {
        return this.delegate.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return this.delegate.getMinY();
    }

    @Override
    public void spawnOriginalMobs(final WorldGenRegion worldGenRegion) {
        this.delegate.spawnOriginalMobs(worldGenRegion);
    }

    @Override
    public void addDebugScreenInfo(final List<String> result, final RandomState randomState,
            final BlockPos feetPos, final SamplerContext samplerContext) {
        long chunks = this.chunkCount.get();
        if (chunks > 0) {
            result.add(String.format(java.util.Locale.ROOT,
                    "Rust chunkgen: %d chunks, rust %.2f ms/chunk, write %.2f ms/chunk",
                    chunks, this.rustNanos.get() / 1.0e6 / chunks,
                    this.writeNanos.get() / 1.0e6 / chunks));
        }
    }

    /**
     * Timing and concurrency summary, reset by the caller.
     *
     * {@code peak} is the highest number of overlapping terrain fills; a value
     * above 1 is the evidence that generation really is multi-threaded.
     */
    public String takeTimings() {
        long chunks = this.chunkCount.getAndSet(0);
        long rust = this.rustNanos.getAndSet(0);
        long write = this.writeNanos.getAndSet(0);
        int peakConcurrency = this.peak.getAndSet(0);
        int threadCount = this.threads.size();
        this.threads.clear();
        if (chunks == 0) {
            return "chunkgen: no Rust chunks generated yet";
        }
        return String.format(java.util.Locale.ROOT,
                "chunkgen: chunks=%d rust=%.3f ms/chunk write=%.3f ms/chunk total=%.3f ms/chunk"
                        + " peak_concurrency=%d threads_used=%d",
                chunks, rust / 1.0e6 / chunks, write / 1.0e6 / chunks, (rust + write) / 1.0e6 / chunks,
                peakConcurrency, threadCount);
    }

    /** Every live generator, so a harness can read their counters. */
    private static final java.util.Set<RustChunkGenerator> INSTANCES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Timing and concurrency summary for every live generator.
     *
     * <p>Static so a plugin can read it reflectively without compiling against
     * the fork's classes; it also resets the counters, so consecutive calls
     * describe the interval since the previous one.</p>
     */
    public static String describeAll() {
        StringBuilder out = new StringBuilder();
        for (RustChunkGenerator generator : INSTANCES) {
            if (!out.isEmpty()) {
                out.append(" | ");
            }
            out.append(generator.takeTimings());
        }
        return out.isEmpty() ? "chunkgen: no generator instances" : out.toString();
    }

    /** The Rust handle, or null before the first chunk. */
    public RustNative.@Nullable Generator generatorHandle() {
        return this.generator.get();
    }

    /** Section height Rust is configured for, for diagnostics. */
    public int rustHeight() {
        return this.delegate.getGenDepth();
    }

    /** Section index base, for diagnostics. */
    public int sectionBaseY() {
        return SectionPos.sectionToBlockCoord(
                Math.floorDiv(this.delegate.getMinY(), 16));
    }
}
