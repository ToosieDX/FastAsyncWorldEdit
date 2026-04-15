package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v26_1.regen;

import com.fastasyncworldedit.bukkit.adapter.Regenerator;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkCache;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.adapter.Refraction;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.RegenOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class PaperweightRegen extends Regenerator {

    private static final Field serverWorldsField;
    private static final Field paperConfigField;
    private static final Field generatorSettingBaseSupplierField;


    static {
        try {
            serverWorldsField = CraftServer.class.getDeclaredField("worlds");
            serverWorldsField.setAccessible(true);

            Field tmpPaperConfigField;
            try { //only present on paper
                tmpPaperConfigField = Level.class.getDeclaredField("paperConfig");
                tmpPaperConfigField.setAccessible(true);
            } catch (Exception e) {
                tmpPaperConfigField = null;
            }
            paperConfigField = tmpPaperConfigField;

            generatorSettingBaseSupplierField = NoiseBasedChunkGenerator.class.getDeclaredField(Refraction.pickName(
                    "settings", "e"));
            generatorSettingBaseSupplierField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    //runtime
    private ServerLevel originalServerWorld;
    private ServerLevel freshWorld;
    private LevelStorageSource.LevelStorageAccess session;

    private Path tempDir;

    public PaperweightRegen(
            World originalBukkitWorld,
            Region region,
            Extent target,
            RegenOptions options
    ) {
        super(originalBukkitWorld, region, target, options);
    }

    @Override
    protected void runTasks(final BooleanSupplier shouldKeepTicking) {
        while (shouldKeepTicking.getAsBoolean()) {
            if (!this.freshWorld.getChunkSource().pollTask()) {
                return;
            }
        }
    }

    @Override
    protected boolean prepare() {
        this.originalServerWorld = ((CraftWorld) originalBukkitWorld).getHandle();
        seed = options.getSeed().orElse(originalServerWorld.getSeed());
        return true;
    }

    @Override
    protected boolean initNewWorld() throws Exception {
        // Paper 26.1 refactored world loading around PaperWorldLoader /
        // SavedDataStorage / LoadedWorldData. The ServerLevel constructor
        // now demands those objects instead of a PrimaryLevelData + RandomSequences
        // pair, and LevelData no longer exposes settings/worldGenOptions/etc.
        // Reconstructing a "temporary regen world" against this new API is
        // a significant port that is out of scope for the initial 26.1
        // compatibility build, so regen is disabled here until upstream FAWE
        // publishes a real port.
        throw new UnsupportedOperationException(
                "Region regeneration (//regen) is not yet supported on Paper 26.1. "
                        + "All other FastAsyncWorldEdit features are available."
        );
    }

    @Override
    protected void cleanup() {
        try {
            session.close();
        } catch (Exception ignored) {
        }

        //shutdown chunk provider
        try {
            Fawe.instance().getQueueHandler().sync(() -> {
                try {
                    freshWorld.getChunkSource().getDataStorage().cache.clear();
                    freshWorld.getChunkSource().close(false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception ignored) {
        }

        //remove world from server
        try {
            Fawe.instance().getQueueHandler().sync(this::removeWorldFromWorldsMap);
        } catch (Exception ignored) {
        }

        //delete directory
        try {
            SafeFiles.tryHardToDeleteDir(tempDir);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected IChunkCache<IChunkGet> initSourceQueueCache() {
        return new ChunkCache<>(BukkitAdapter.adapt(freshWorld.getWorld()));
    }

    //util
    @SuppressWarnings("unchecked")
    private void removeWorldFromWorldsMap() {
        try {
            Map<String, World> map = (Map<String, World>) serverWorldsField.get(Bukkit.getServer());
            map.remove("faweregentempworld");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private ResourceKey<LevelStem> getWorldDimKey(World.Environment env) {
        return switch (env) {
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> LevelStem.OVERWORLD;
        };
    }

}
