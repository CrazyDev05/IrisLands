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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

//TODO add closed flag

@Data
public class RegionManager {
	private final Map<@NonNull Long, @NonNull Region> regions = new HashMap<>();
	private final List<@NonNull Long> toUnload = new ArrayList<>();

	private final ReentrantLock unloadLock = new ReentrantLock(true);
	private final AtomicBoolean ioTrim = new AtomicBoolean();
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
		ioTrim.set(true);
		unloadLock.lock();
		try {
			regions.forEach((key, value) -> {
				if (value.getLastUse() < maxLastUse)
					toUnload.add(key);
			});
		} finally {
			unloadLock.unlock();
			ioTrim.set(false);
		}
	}

	public void unload() {
		ioTrim.set(true);
		unloadLock.lock();
		try {
			//TODO add unload
			toUnload.clear();
		} finally {
			unloadLock.unlock();
			ioTrim.set(false);
		}
	}

	public void save(Chunk chunk, boolean overwrite) {
		plugin.getService().submit(() ->
				get(chunk.getX() >> 5, chunk.getZ() >> 5)
						.save(chunk, overwrite));
	}

	public void load(Chunk chunk, boolean delete) {
		plugin.getService().submit(() ->
				get(chunk.getX() >> 5, chunk.getZ() >> 5)
						.load(chunk, delete));
	}

	public void close() {
		for (var region : regions.values()) {
			region.save();
		}
		regions.clear();
	}

	@RegionCoordinates
	private Region get(int x, int z) {
		if (ioTrim.get()) {
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
