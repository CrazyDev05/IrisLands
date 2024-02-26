package de.crazydev22.irislands.data;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.mantle.MantleChunk;
import com.volmit.iris.util.parallel.HyperLock;
import de.crazydev22.irislands.MantleWrapper;
import lombok.Data;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.bukkit.Chunk;

import java.io.*;
import java.util.concurrent.atomic.AtomicReferenceArray;

@Data
public class Region {
	private final AtomicReferenceArray<MantleChunk> mantleChunks = new AtomicReferenceArray<>(1024);
	private final AtomicReferenceArray<Clipboard> worldChunks = new AtomicReferenceArray<>(1024);
	private final RegionManager manager;
	private final HyperLock hyperLock = new HyperLock();
	private long lastUse = System.currentTimeMillis();
	private final int x, z;

	public Region(RegionManager manager, int x, int z) {
		this.manager = manager;
		this.x = x;
		this.z = z;

		var file = manager.getFile(x, z);
		if (!file.exists()) return;
		try (var din = new DataInputStream(new LZ4BlockInputStream(new FileInputStream(file)))) {
			for (int i = 0; i < 1024; i++) {
				if (din.readBoolean() && manager.getMantle() != null)
					mantleChunks.set(i, new MantleChunk(manager.getMantle().getWorldHeight() >> 4, din));
				if (din.readBoolean())
					worldChunks.set(i, BuiltInClipboardFormat.FAST.getReader(din).read());
			}
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public void load(Chunk chunk, boolean delete) {
		lastUse = System.currentTimeMillis();
		int index = index(chunk.getX(), chunk.getZ());
		hyperLock.withLong(index, () -> {
			chunk.addPluginChunkTicket(manager.getPlugin());
			try {
				var mantle = manager.getMantle();
				if (mantle != null) {
					var mantleChunk = mantleChunks.get(index);
					getWrapper().setChunk(mantle, chunk.getX(), chunk.getZ(), mantleChunk);
					if (delete) mantleChunks.set(index, null);
				}

				var worldChunk = worldChunks.get(index);
				if (worldChunk != null) {
					try (var editSession = WorldEdit.getInstance().newEditSession(new BukkitWorld(chunk.getWorld()))) {
						var operation = new ClipboardHolder(worldChunk)
								.createPaste(editSession)
								.to(BlockVector3.at(x << 4, chunk.getWorld().getMinHeight(), z << 4))
								.ignoreAirBlocks(false)
								.build();
						Operations.complete(operation);
						if (delete) worldChunks.set(index, null);
					}
				}
			} finally {
				chunk.removePluginChunkTicket(manager.getPlugin());
				hyperLock.unlock(chunk.getX(), chunk.getZ());
			}
		});
	}

	public void save(Chunk chunk, boolean overwrite) {
		lastUse = System.currentTimeMillis();
		int index = index(chunk.getX(), chunk.getZ());
		hyperLock.withLong(index, () -> {
			chunk.addPluginChunkTicket(manager.getPlugin());
			try {
				var mantle = manager.getMantle();
				if (mantle != null) {
					if (mantleChunks.get(index) == null || overwrite) {
						mantleChunks.set(index, mantle.getChunk(chunk.getX(), chunk.getZ()));
					}
				}

				if (worldChunks.get(index) == null || overwrite) {
					var world = new BukkitWorld(chunk.getWorld());
					try (var editSession = WorldEdit.getInstance().newEditSession(world)) {
						var region = new CuboidRegion(world,
								BlockVector3.at(x << 4, chunk.getWorld().getMinHeight(), z << 4),
								BlockVector3.at((x << 4) + 15, chunk.getWorld().getMaxHeight(), (z << 4) + 15));
						try (var clipboard = new BlockArrayClipboard(region)) {
							var copy = new ForwardExtentCopy(editSession, region, clipboard, region.getMinimumPoint());
							Operations.complete(copy);
						}
					}
				}
			} finally {
				chunk.removePluginChunkTicket(manager.getPlugin());
				hyperLock.unlock(chunk.getX(), chunk.getZ());
			}
		});
	}

	private MantleWrapper getWrapper() {
		return manager.getPlugin().getWrapper();
	}

	public void save() {
		try (var dos = new DataOutputStream(new LZ4BlockOutputStream(new FileOutputStream(manager.getFile(x, z))))) {
			for (int i = 0; i < 1024; i++) {
				var mantle = mantleChunks.get(i);
				if (mantle != null) {
					dos.writeBoolean(true);
					mantle.write(dos);
				} else {
					dos.writeBoolean(false);
				}
				dos.flush();
				var world = worldChunks.get(i);
				if (world != null) {
					dos.writeBoolean(true);
					BuiltInClipboardFormat.FAST.getWriter(dos).write(world);
				} else {
					dos.writeBoolean(false);
				}
				dos.flush();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static int index(int x, int z) {
		return Cache.to1D(x, z, 0, 32, 32);
	}
}
