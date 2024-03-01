package de.crazydev22.irislands;

import de.crazydev22.irislands.data.RegionManager;
import de.crazydev22.irislands.util.Executor;
import de.crazydev22.irislands.util.ThreadLatch;
import de.crazydev22.irislands.util.MantleWrapper;
import lombok.Getter;
import me.angeschossen.wildregeneration.api.events.chunk.ChunkRegenerateEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Getter
public final class IrisLands extends JavaPlugin implements Listener {
	private final ExecutorService service = new Executor("IrisLands", 6);
	private final ReentrantLock managerLock = new ReentrantLock(true);
	private final Map<World, RegionManager> managers = new ConcurrentHashMap<>();
	private final AtomicBoolean closed = new AtomicBoolean();
	private ThreadLatch unloadThread;
	private ThreadLatch trimThread;
	private MantleWrapper wrapper;

	@Override
	public void onEnable() {
		try {
			wrapper = new MantleWrapper();
		} catch (NoSuchMethodException | NoSuchFieldException e) {
			throw new RuntimeException(e);
		}
		getServer().getPluginManager().registerEvents(this, this);
		closed.set(false);
		Runtime.getRuntime().addShutdownHook(new Thread(this::onDisable));

		unloadThread = new ThreadLatch(() -> {
			while (!closed.get()) {
				long sleep = 1000;
				for (var manager : managers.values()) {
					if (manager == null)
						continue;
					long time = System.currentTimeMillis();
					manager.unload();
					sleep -= System.currentTimeMillis() - time;
				}
				try {
					Thread.sleep(Math.max(0, sleep));
				} catch (InterruptedException ignored) {}
			}
		}, "IrisLands unload");
		unloadThread.setDaemon(true);
		unloadThread.start();

		trimThread = new ThreadLatch(() -> {
			while (!closed.get()) {
				long sleep = 1000;
				for (var manager : managers.values()) {
					if (manager == null)
						continue;
					long time = System.currentTimeMillis();
					manager.trim(30*60*1000);
					sleep -= System.currentTimeMillis() - time;
				}
				try {
					Thread.sleep(Math.max(0, sleep));
				} catch (InterruptedException ignored) {}
			}
		}, "IrisLands trim");
		trimThread.setDaemon(true);
		trimThread.start();
	}

	@Override
	public void onDisable() {
		closed.set(true);
		service.shutdown();
		if (trimThread != null) {
			try {
				trimThread.await();
			} catch (InterruptedException ignored) {}
		}
		if (unloadThread != null) {
			try {
				unloadThread.await();
			} catch (InterruptedException ignored) {}
		}
		managerLock.lock();
		try {
			for (var manager : managers.values()) {
				if (manager == null)
					continue;
				manager.close();
			}
		} finally {
			managers.clear();
			managerLock.unlock();
		}
	}

	@EventHandler
	public void onWorldUnload(WorldUnloadEvent event) {
		var manager = managers.remove(event.getWorld());
		if (manager != null) {
			service.submit(() -> {
				managerLock.lock();
				try {
					manager.close();
				} finally {
					managerLock.unlock();
				}
			});
		}
	}

	@EventHandler
	public void onChunkLoad(ChunkLoadEvent event) {
		getManager(event.getChunk()).thenAccept(manager -> manager.save(event.getChunk(), false));
	}

	@EventHandler
	public void onChunkRegenerate(ChunkRegenerateEvent event) {
		var eventChunk = event.getChunk();
		event.setCancelled(true);
		var chunk = eventChunk.getWorld().getWorld().getChunkAt(eventChunk.getX(), eventChunk.getZ());
		getManager(chunk).thenAccept(manager -> manager.load(chunk, false));
	}

	private CompletableFuture<RegionManager> getManager(Chunk chunk) {
		return getManager(chunk.getWorld());
	}

	private CompletableFuture<RegionManager> getManager(World world) {
		RegionManager m = managers.get(world);
		if (m != null)
			return CompletableFuture.completedFuture(m);
		CompletableFuture<RegionManager> future = new CompletableFuture<>();
		service.submit(() -> {
			RegionManager manager = managers.get(world);
			if (manager != null) {
				future.complete(manager);
				return;
			}
			managerLock.lock();
			try {
				manager = new RegionManager(this, world);
				managers.put(world, manager);
				future.complete(manager);
			} finally {
				managerLock.unlock();
			}
		});
		return future;
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
		if (!sender.hasPermission("irislands.admin")) {
			sender.sendMessage("You don't have permission to use this command!");
			return true;
		}
		if (args.length < 4)
			return false;

		World world = Bukkit.getWorld(args[1]);
		if (world == null) {
			sender.sendMessage("World not found!");
			return true;
		}
		Chunk chunk = getChunk(args);

		if (args[0].equalsIgnoreCase("load")) {
			if (chunk == null) {
				sender.sendMessage("Chunk not found!");
				return true;
			}
			sender.sendMessage("Loading chunk...");
			getManager(chunk).thenAccept(manager ->
					manager.load(chunk, args.length == 4 || Boolean.parseBoolean(args[4]))
							.thenAccept(changed -> {
								if (changed) sender.sendMessage("Loaded chunk!");
								else sender.sendMessage("Chunk already loaded!");
							}));
			return true;
		} else if (args[0].equalsIgnoreCase("save")) {
			if (chunk == null) {
				sender.sendMessage("Chunk not found!");
				return true;
			}
			sender.sendMessage("Saving chunk...");
			getManager(chunk).thenAccept(manager ->
							manager.save(chunk, args.length != 4 && Boolean.parseBoolean(args[4]))
									.thenAccept(changed -> {
										if (changed) sender.sendMessage("Saved chunk!");
										else sender.sendMessage("Chunk already saved!");
									}));
			return true;
		} else if (args[0].equalsIgnoreCase("clear")) {
			getManager(world).thenAccept(manager -> {
				manager.clear();
				sender.sendMessage("Cleared all regions!");
			});
		}
		return false;
	}

	private Chunk getChunk(String[] args) {
		try {
			World world = Bukkit.getWorld(args[1]);
			return world != null ? world.getChunkAt(Integer.parseInt(args[2]), Integer.parseInt(args[3])) : null;
		} catch (Throwable ignored) {}
		return null;
	}

	@Override
	public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
		if (!sender.hasPermission("irislands.admin"))
			return null;
		Location loc = null;
		if (sender instanceof Entity e)
			loc = e.getLocation();

		List<String> list = switch (args.length) {
			case 1 -> List.of("load", "save", "clear");
			case 2 -> Bukkit.getWorlds().stream().map(World::getName).toList();
			case 3 -> List.of(loc != null ? String.valueOf(loc.getChunk().getX()) : "0");
			case 4 -> List.of(loc != null ? String.valueOf(loc.getChunk().getZ()) : "0");
			case 5 -> List.of("true", "false");
			default -> null;
		};
		if (list != null) {
			list = new ArrayList<>(list);
			list.removeIf(s -> !s.startsWith(args[args.length - 1]));
		}
		return list;
	}
}
