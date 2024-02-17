package de.crazydev22.irislands;

import com.volmit.iris.engine.data.cache.Cache;
import com.volmit.iris.util.documentation.ChunkCoordinates;
import com.volmit.iris.util.mantle.Mantle;
import com.volmit.iris.util.mantle.MantleChunk;
import com.volmit.iris.util.mantle.TectonicPlate;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReferenceArray;

@SuppressWarnings("unchecked")
public class MantleWrapper {
    private final Method getPlate;
    private final Field chunks;

    public MantleWrapper() throws NoSuchMethodException, NoSuchFieldException {
        getPlate = Mantle.class.getDeclaredMethod("get", int.class, int.class);
        getPlate.setAccessible(true);

        chunks = TectonicPlate.class.getDeclaredField("chunks");
        chunks.setAccessible(true);
    }

    public MantleChunk read(Mantle mantle, InputStream in) {
        try {
            return new MantleChunk(mantle.getWorldHeight() >> 4, new DataInputStream(in));
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public void write(MantleChunk chunk, OutputStream out) {
        try {
            chunk.write(new DataOutputStream(out));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @ChunkCoordinates
    public MantleChunk getChunk(Mantle mantle, int x, int z) {
        try {
            return ((TectonicPlate) getPlate.invoke(mantle, x >> 5, z>> 5))
                    .getOrCreate(x & 31, z & 31);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @ChunkCoordinates
    public void setChunk(Mantle mantle, int x, int z, MantleChunk chunk) {
        try {
            var array = (AtomicReferenceArray<MantleChunk>) chunks.get(getPlate.invoke(mantle, x >> 4 >> 5, z >> 4 >> 5));
            array.set(Cache.to1D(x >> 4 & 31, z >> 4 & 31, 0, 32, 32), chunk);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }
}
