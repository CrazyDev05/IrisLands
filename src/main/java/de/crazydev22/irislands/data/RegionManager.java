package de.crazydev22.irislands.data;

import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.documentation.RegionCoordinates;
import com.volmit.iris.util.mantle.Mantle;
import com.volmit.iris.util.parallel.HyperLock;
import de.crazydev22.irislands.IrisLands;
import lombok.Data;
import lombok.NonNull;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

@Data
public class RegionManager {
	private final Map<@NonNull Long, @NonNull Region> regions = new HashMap<>();
	private final HashSet<@NonNull Long> toUnload = new HashSet<>();

	private final ReentrantLock unloadLock = new ReentrantLock(true);
	private final AtomicBoolean closed = new AtomicBoolean();
	private final HyperLock hyperLock = new HyperLock();

	private final IrisLands plugin;
	private final Mantle mantle;
	private final File dataFolder;

	public RegionManager(IrisLands plugin, World world) {
		this.plugin = plugin;
		this.mantle = getMantle(world);
		this.dataFolder = new File(world.getWorldFolder(), "backups");
	}

	public void trim(long maxLastUse) {
		unloadLock.lock();
		try {
			regions.forEach((key, value) -> {
				if (value.getLastUse() - System.currentTimeMillis() < maxLastUse)
					toUnload.add(key);
			});
		} finally {
			unloadLock.unlock();
		}
	}

	public boolean clear() {
		unloadLock.lock();
		boolean result;
		try {
			result = deleteDir(dataFolder);
			regions.clear();
			toUnload.clear();
		} finally {
			unloadLock.unlock();
		}
		return result;
	}

	private static boolean deleteDir(File file) {
		File[] contents = file.listFiles();
		if (contents != null) {
			for (File f : contents) {
				try {
					if (!Files.isSymbolicLink(f.toPath())) {
						deleteDir(f);
					}
				} catch (Exception ignored) {
				}
			}
		}
		return file.delete();
	}

	public void unload() {
		unloadLock.lock();
		try {
			for (Long key : toUnload.toArray(Long[]::new)) {
				Region region = regions.get(key);
				try {
					if (region != null)
						region.save();
				} catch (Throwable e) {
					getPlugin().getLogger().log(Level.SEVERE, "Failed to save region " + key, e);
				}
				regions.remove(key);
				toUnload.remove(key);
			}
		} finally {
			unloadLock.unlock();
		}
	}

	@NonNull
	public CompletableFuture<@NonNull Boolean> save(Chunk chunk, boolean overwrite) {
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		plugin.getService().submit(() -> {
			future.complete(get(chunk.getX() >> 5, chunk.getZ() >> 5)
					.save(chunk, overwrite));
		});
		return future;
	}

	@NonNull
	public CompletableFuture<@NonNull Boolean> load(Chunk chunk, boolean delete) {
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		plugin.getService().submit(() -> {
			future.complete(get(chunk.getX() >> 5, chunk.getZ() >> 5)
					.load(chunk, delete));
		});
		return future;
	}

	public void close() {
		try {
			trim(0);
			unload();
		} finally {
			closed.set(true);
		}
	}

	@RegionCoordinates
	private Region get(int x, int z) {
		if (closed.get())
			throw new IllegalStateException("RegionManager is closed");
		if (unloadLock.isLocked()) {
			try {
				return getSafe(x, z).get();
			} catch (InterruptedException | ExecutionException e) {
				getPlugin().getLogger().log(Level.WARNING, "Failed to get region", e);
			}
		}

		Region r = regions.get(Cache.key(x, z));
		if (r != null)
			return r;

		try {
			return getSafe(x, z).get();
		} catch (ExecutionException | InterruptedException e) {
			getPlugin().getLogger().log(Level.WARNING, "Failed to get region", e);
		}
		return get(x, z);
	}

	@RegionCoordinates
	private Future<Region> getSafe(int x, int z) {
		long key = Cache.key(x, z);
		Region r = regions.get(key);
		if (r != null)
			return CompletableFuture.completedFuture(r);

		return plugin.getService().submit(() -> hyperLock.withResult(x, z, () -> {
			Region region = regions.get(key);
			if (region != null)
				return region;
			region = new Region(this, x, z);
			regions.put(key, region);
			return region;
		}));
	}

	@RegionCoordinates
	public File getFile(int x, int z) {
		File file = new File(dataFolder, File.separator + x + "_" + z + ".lz4b");
		if (!file.getParentFile().exists() && !file.getParentFile().mkdirs())
			throw new RuntimeException("Failed to create directory: " + file.getParentFile());
		return file;
	}

	private static Mantle getMantle(World world) {
		var platform = IrisToolbelt.access(world);
		if (platform == null)
			return null;
		var engine = platform.getEngine();
		return engine != null ? engine.getMantle().getMantle() : null;
	}
}
