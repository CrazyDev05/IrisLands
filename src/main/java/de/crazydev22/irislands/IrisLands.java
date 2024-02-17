package de.crazydev22.irislands;

import com.fastasyncworldedit.core.function.block.SimpleBlockCopy;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.documentation.ChunkCoordinates;
import com.volmit.iris.util.mantle.Mantle;
import me.angeschossen.lands.api.events.ChunkDeleteEvent;
import me.angeschossen.lands.api.events.ChunkPostClaimEvent;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

public final class IrisLands extends JavaPlugin implements Listener {
    private final ExecutorService service = new ForkJoinPool();
    private final Map<Long, Future<?>> futures = new HashMap<>();
    private MantleWrapper wrapper;

    @Override
    public void onEnable() {
        try {
            wrapper = new MantleWrapper();
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        service.shutdown();
    }

    @EventHandler
    public void onPostClaim(ChunkPostClaimEvent event) {
        var world = event.getWorld().getWorld();
        var mantle = getMantle(world);
        if (mantle == null) return;
        Chunk chunk = world.getChunkAt(event.getX(), event.getZ());
        submit(chunk, () -> {
            chunk.addPluginChunkTicket(this);

            try {
                try (var out = new LZ4BlockOutputStream(new FileOutputStream(getFile(world, true, event.getX(), event.getZ())))) {
                    wrapper.write(wrapper.getChunk(mantle, event.getX(), event.getZ()), out);
                }
                try (var out = new FileOutputStream(getFile(world, false, event.getX(), event.getZ()));
                     var editSession = WorldEdit.getInstance().newEditSession(new BukkitWorld(world))) {
                    var region = new CuboidRegion(new BukkitWorld(world),
                            BlockVector3.at(event.getX() << 4, world.getMinHeight(), event.getZ() << 4),
                            BlockVector3.at((event.getX() << 4) + 15, world.getMaxHeight(), (event.getZ() << 4) + 15));
                    try (var clipboard = new BlockArrayClipboard(region)) {
                        var copy = new ForwardExtentCopy(editSession, region, clipboard, region.getMinimumPoint());
                        Operations.complete(copy);
                        clipboard.save(out, BuiltInClipboardFormat.FAST);
                    }
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            } finally {
                chunk.removePluginChunkTicket(this);
            }
        });
    }

    @EventHandler
    public void onChunkDelete(ChunkDeleteEvent event) {
        var world = event.getWorld();
        var mantle = getMantle(world);
        if (mantle == null) return;
        Chunk chunk = world.getChunkAt(event.getX(), event.getZ());
        submit(chunk, () -> {
            chunk.addPluginChunkTicket(this);

            try {
                var mantleFile = getFile(world, true, event.getX(), event.getZ());
                try (var in = new LZ4BlockInputStream(new FileInputStream(mantleFile))) {
                    wrapper.setChunk(mantle, event.getX(), event.getZ(), wrapper.read(mantle, in));
                } finally {
                    mantleFile.delete();
                }

                var chunkFile = getFile(world, false, event.getX(), event.getZ());
                try (var reader = BuiltInClipboardFormat.FAST.getReader(new FileInputStream(chunkFile));
                     var editSession = WorldEdit.getInstance().newEditSession(new BukkitWorld(world))) {
                    var operation = new ClipboardHolder(reader.read())
                            .createPaste(editSession)
                            .to(BlockVector3.at(event.getX() << 4, world.getMinHeight(), event.getZ() << 4))
                            .ignoreAirBlocks(false)
                            .build();
                    Operations.complete(operation);
                } finally {
                    chunkFile.delete();
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            } finally {
                chunk.removePluginChunkTicket(this);
            }
        });
    }

    private void submit(Chunk chunk, Runnable runnable) {
        long chunkId = Cache.key(chunk);
        var future = futures.remove(chunkId);
        if (future != null) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        future = service.submit(runnable);
        futures.put(chunkId, future);
    }

    @ChunkCoordinates
    private File getFile(World world, boolean mantle, int x, int z) {
        File file = new File(getDataFolder(), world.getName() + File.separator + x + "_" + z + (mantle ? ".lz4b" : ".schem"));
        if (!file.getParentFile().exists())
            file.getParentFile().mkdirs();
        return file;
    }


    private Mantle getMantle(World world) {
        var platform = IrisToolbelt.access(world);
        if (platform == null)
            return null;
        var engine = platform.getEngine();
        return engine != null ? engine.getMantle().getMantle() : null;
    }
}
