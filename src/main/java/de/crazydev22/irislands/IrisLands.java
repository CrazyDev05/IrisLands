package de.crazydev22.irislands;

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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.logging.Level;

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
	public void onChunkLoad(ChunkLoadEvent event) {
		save(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ(), false);
	}

	@EventHandler
	public void onPostClaim(ChunkPostClaimEvent event) {
		save(event.getWorld().getWorld(), event.getX(), event.getZ(), false);
	}

	@EventHandler
	public void onChunkDelete(ChunkDeleteEvent event) {
		load(event.getWorld(), event.getX(), event.getZ(), false);
	}

	@ChunkCoordinates
	public void load(World world, int x, int z, boolean delete) {
		Chunk chunk = world.getChunkAt(x, z);
		submit(chunk, () -> {
			chunk.addPluginChunkTicket(this);

			try {
				var mantle = getMantle(world);
				if (mantle != null) {
					var mantleFile = getFile(world, true, x, z);
					try (var in = new LZ4BlockInputStream(new FileInputStream(mantleFile))) {
						wrapper.setChunk(mantle, x, z, wrapper.read(mantle, in));
					} catch (Throwable e) {
						getLogger().log(Level.WARNING, "Failed to load mantle chunk: " + x + ", " + z, e);
					} finally {
						if (delete && !mantleFile.delete()) {
							getLogger().warning("Failed to delete mantle chunk: " + x + ", " + z);
						}
					}
				}

				var chunkFile = getFile(world, false, x, z);
				try (var reader = BuiltInClipboardFormat.FAST.getReader(new FileInputStream(chunkFile));
					 var editSession = WorldEdit.getInstance().newEditSession(new BukkitWorld(world))) {
					var operation = new ClipboardHolder(reader.read())
							.createPaste(editSession)
							.to(BlockVector3.at(x << 4, world.getMinHeight(), z << 4))
							.ignoreAirBlocks(false)
							.build();
					Operations.complete(operation);
				} catch (Throwable e) {
					getLogger().log(Level.WARNING, "Failed to load world chunk: " + x + ", " + z, e);
				} finally {
					if (delete && !chunkFile.delete()) {
						getLogger().warning("Failed to delete world chunk: " + x + ", " + z);
					}
				}
			} catch (Throwable e) {
				throw new RuntimeException(e);
			} finally {
				chunk.removePluginChunkTicket(this);
			}
		});
	}

	@ChunkCoordinates
	public void save(World world, int x, int z, boolean overwrite) {
		Chunk chunk = world.getChunkAt(x, z);
		submit(chunk, () -> {
			chunk.addPluginChunkTicket(this);

			try {
				var mantle = getMantle(world);
				if (mantle != null) {
					if (!getFile(world, true, x, z).exists() || overwrite) {
						try (var out = new LZ4BlockOutputStream(new FileOutputStream(getFile(world, true, x, z)))) {
							wrapper.write(wrapper.getChunk(mantle, x, z), out);
						} catch (Throwable e) {
							getLogger().log(Level.SEVERE, "Failed to save mantle chunk: " + x + ", " + z, e);
						}
					}
				}

				if (!getFile(world, false, x, z).exists() || overwrite) {
					try (var out = new FileOutputStream(getFile(world, false, x, z));
						 var editSession = WorldEdit.getInstance().newEditSession(new BukkitWorld(world))) {
						var region = new CuboidRegion(new BukkitWorld(world),
								BlockVector3.at(x << 4, world.getMinHeight(), z << 4),
								BlockVector3.at((x << 4) + 15, world.getMaxHeight(), (z << 4) + 15));
						try (var clipboard = new BlockArrayClipboard(region)) {
							var copy = new ForwardExtentCopy(editSession, region, clipboard, region.getMinimumPoint());
							Operations.complete(copy);
							clipboard.save(out, BuiltInClipboardFormat.FAST);
						}
					} catch (Throwable e) {
						getLogger().log(Level.SEVERE, "Failed to save world chunk: " + x + ", " + z, e);
					}
				}
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
                getLogger().log(Level.WARNING, "Failed chunk action on chunk " + chunk, e);
			}
		}
		future = service.submit(runnable);
		futures.put(chunkId, future);
	}

	@ChunkCoordinates
	private File getFile(World world, boolean mantle, int x, int z) {
		File file = new File(getDataFolder(), world.getName() + File.separator + x + "_" + z + (mantle ? ".lz4b" : ".schem"));
		if (!file.getParentFile().exists() && !file.getParentFile().mkdirs())
            throw new RuntimeException("Failed to create directory: " + file.getParentFile());
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
