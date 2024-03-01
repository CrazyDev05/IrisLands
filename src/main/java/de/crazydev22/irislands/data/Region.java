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
import de.crazydev22.irislands.util.MantleWrapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.bukkit.Chunk;

import java.io.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Level;

@Data
public class Region {
	private final AtomicReferenceArray<MantleChunk> mantleChunks = new AtomicReferenceArray<>(1024);
	private final AtomicReferenceArray<String> worldChunks = new AtomicReferenceArray<>(1024);
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
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
					worldChunks.set(i, din.readUTF());
			}
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public boolean load(Chunk chunk, boolean delete) {
		lastUse = System.currentTimeMillis();
		int index = index(chunk.getX(), chunk.getZ());
		AtomicBoolean changed = new AtomicBoolean(false);
		hyperLock.withLong(index, () -> {
			chunk.addPluginChunkTicket(manager.getPlugin());
			try {
				var mantle = manager.getMantle();
				if (mantle != null) {
					var mantleChunk = mantleChunks.get(index);
					getWrapper().setChunk(mantle, chunk.getX(), chunk.getZ(), mantleChunk);
					if (delete) mantleChunks.set(index, null);
					changed.set(true);
				}

				var worldChunk = worldChunks.get(index);
				if (worldChunk != null) {
					try (var editSession = WorldEdit.getInstance().newEditSession(new BukkitWorld(chunk.getWorld()))) {
						var operation = new ClipboardHolder(fromBytes(worldChunk))
								.createPaste(editSession)
								.to(BlockVector3.at(chunk.getX() << 4, chunk.getWorld().getMinHeight(), chunk.getZ() << 4))
								.ignoreAirBlocks(false)
								.build();
						Operations.complete(operation);
						if (delete) worldChunks.set(index, null);
						changed.set(true);
					}
				}
			} catch (Throwable e) {
				manager.getPlugin().getLogger().log(Level.SEVERE, "Failed to load region chunk " + chunk.getX() + ", " + chunk.getZ(), e);
			} finally {
				chunk.removePluginChunkTicket(manager.getPlugin());
			}
		});
		return changed.get();
	}

	public boolean save(Chunk chunk, boolean overwrite) {
		lastUse = System.currentTimeMillis();
		int index = index(chunk.getX(), chunk.getZ());
		if (!overwrite && worldChunks.get(index) != null && !worldChunks.get(index).isBlank() && (manager.getMantle() == null || mantleChunks.get(index) != null))
			return false;
		AtomicBoolean changed = new AtomicBoolean(false);
		hyperLock.withLong(index, () -> {
			chunk.addPluginChunkTicket(manager.getPlugin());
			try {
				var mantle = manager.getMantle();
				if (mantle != null) {
					if (mantleChunks.get(index) == null || overwrite) {
						mantleChunks.set(index, copy(mantle.getWorldHeight() >> 4, mantle.getChunk(chunk.getX(), chunk.getZ())));
						changed.set(true);
					}
				}

				if (worldChunks.get(index) == null || worldChunks.get(index).isBlank() || overwrite) {
					var world = new BukkitWorld(chunk.getWorld());
					try (var editSession = WorldEdit.getInstance().newEditSession(world)) {
						var region = new CuboidRegion(world,
								BlockVector3.at(chunk.getX() << 4, chunk.getWorld().getMinHeight(), chunk.getZ() << 4),
								BlockVector3.at((chunk.getX() << 4) + 15, chunk.getWorld().getMaxHeight(), (chunk.getZ() << 4) + 15));
						try (var clipboard = new BlockArrayClipboard(region)) {
							var copy = new ForwardExtentCopy(editSession, region, clipboard, region.getMinimumPoint());
							Operations.complete(copy);
							worldChunks.set(index, toBase64(clipboard));
							changed.set(true);
						}
					}
				}
			} catch (Throwable e) {
				manager.getPlugin().getLogger().log(Level.SEVERE, "Failed to save region chunk " + chunk.getX() + ", " + chunk.getZ(), e);
			} finally {
				chunk.removePluginChunkTicket(manager.getPlugin());
			}
		});
		return changed.get();
	}

	private MantleWrapper getWrapper() {
		return manager.getPlugin().getWrapper();
	}

	public void save() throws IOException {
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
				if (world != null && !world.isBlank()) {
					dos.writeBoolean(true);
					dos.writeUTF(world);
				} else {
					dos.writeBoolean(false);
				}
				dos.flush();
			}
		}
	}

	private static MantleChunk copy(int sectionHeight, MantleChunk chunk) throws IOException, ClassNotFoundException {
		if (chunk == null)
			return null;
		byte[] data;
		try (var bytes = new ByteArrayOutputStream(); var dos = new DataOutputStream(bytes)) {
			chunk.write(dos);
			data = bytes.toByteArray();
		}
		try (var bytes = new ByteArrayInputStream(data); var din = new DataInputStream(bytes)) {
			return new MantleChunk(sectionHeight, din);
		}
	}

	private static String toBase64(Clipboard clipboard) throws IOException {
		try (var out = new ByteArrayOutputStream()) {
			clipboard.save(out, BuiltInClipboardFormat.FAST);
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
	}

	public static Clipboard fromBytes(String base64) throws IOException {
		try (var in = new ByteArrayInputStream(Base64.getDecoder().decode(base64))) {
			return BuiltInClipboardFormat.FAST.getReader(in).read();
		}
	}

	private static int index(int x, int z) {
		return Cache.to1D(x, z, 0, 32, 32);
	}
}
