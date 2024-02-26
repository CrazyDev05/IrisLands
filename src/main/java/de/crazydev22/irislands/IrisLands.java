package de.crazydev22.irislands;

import de.crazydev22.irislands.data.RegionManager;
import lombok.Getter;
import me.angeschossen.wildregeneration.api.events.chunk.ChunkRegenerateEvent;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

@Getter
public final class IrisLands extends JavaPlugin implements Listener {
	private final ExecutorService service = new ForkJoinPool();
	private final Map<World, RegionManager> managers = new HashMap<>();
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
		for (var manager : managers.values()) {
			if (manager == null)
				continue;
			manager.close();
		}
	}

	@EventHandler
	public void onWorldUnload(WorldUnloadEvent event) {
		var manager = managers.remove(event.getWorld());
		if (manager != null) {
			service.submit(manager::close);
		}
	}

	@EventHandler
	public void onChunkLoad(ChunkLoadEvent event) {
		get(event.getChunk()).save(event.getChunk(), false);
	}

	@EventHandler
	public void onChunkRegenerate(ChunkRegenerateEvent event) {
		var eventChunk = event.getChunk();
		event.setCancelled(true);
		var chunk = eventChunk.getWorld().getWorld().getChunkAt(eventChunk.getX(), eventChunk.getZ());
		get(chunk).load(chunk, false);
	}

	private RegionManager get(Chunk chunk) {
		return managers.computeIfAbsent(chunk.getWorld(),
				world -> new RegionManager(this, world));
	}
}
