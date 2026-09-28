package io.github.rantark.factoryflow.sim

import io.github.rantark.factoryflow.data.BuildingType
import io.github.rantark.factoryflow.data.Item
import io.github.rantark.factoryflow.data.Techs
import io.github.rantark.factoryflow.world.Resource
import io.github.rantark.factoryflow.world.World
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Binary save format (gzip-compressed). The terrain is regenerated from the seed, so a
 * save holds only: the seed, depleted resource tiles, explored map, buildings with
 * their contents, core stock, research state, time of day and the camera.
 */
object SaveGame {
    private const val MAGIC = 0x46465356 // "FFSV"
    private const val VERSION = 1

    class Camera(val x: Float, val y: Float, val zoom: Float)

    /** Summary shown in the save-slot list without loading the whole world. */
    class Header(val savedAt: Long, val playTime: Double, val buildings: Int, val researched: Int, val seed: Long)

    fun write(f: Factory, cam: Camera, out: OutputStream) {
        f.fluids.distribute()
        DataOutputStream(GZIPOutputStream(out)).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION)
            // Header
            o.writeLong(System.currentTimeMillis())
            o.writeDouble(f.simTime)
            o.writeInt(f.buildings.size)
            o.writeInt(f.research.done.count { it })
            o.writeLong(f.world.seed)
            // Clock & camera
            o.writeLong(f.tickCount); o.writeFloat(f.dayTime)
            o.writeFloat(cam.x); o.writeFloat(cam.y); o.writeFloat(cam.zoom)
            // Stock
            o.writeShort(Item.COUNT)
            for (v in f.stock) o.writeInt(v)
            // Research
            o.writeShort(Techs.ALL.size)
            for (t in Techs.ALL) { o.writeBoolean(f.research.done[t.index]); o.writeInt(f.research.progress[t.index]) }
            o.writeShort(f.research.current?.index ?: -1)
            o.writeBoolean(f.research.chosen)
            // Resource depletion (only tiles that changed)
            val w = f.world
            var changed = 0
            for (i in w.amount.indices) if (w.amount[i] != w.initialAmount[i]) changed++
            o.writeInt(changed)
            for (i in w.amount.indices) if (w.amount[i] != w.initialAmount[i]) { o.writeInt(i); o.writeInt(w.amount[i]) }
            // Fog of war
            o.writeInt(w.explored.size)
            for (b in w.explored) o.writeBoolean(b)
            // Buildings – each record is length-prefixed so unknown data can be skipped.
            o.writeInt(f.buildings.size)
            val tmp = ByteArrayOutputStream()
            for (b in f.buildings) {
                o.writeShort(b.type.ordinal); o.writeShort(b.x); o.writeShort(b.y); o.writeByte(b.dir)
                tmp.reset()
                DataOutputStream(tmp).use { b.write(it) }
                o.writeInt(tmp.size())
                tmp.writeTo(o)
            }
        }
    }

    fun readHeader(input: InputStream): Header? = try {
        DataInputStream(GZIPInputStream(input)).use { i ->
            if (i.readInt() != MAGIC) return null
            i.readInt()
            Header(i.readLong(), i.readDouble(), i.readInt(), i.readInt(), i.readLong())
        }
    } catch (e: Exception) { null }

    fun read(input: InputStream): Pair<Factory, Camera> {
        DataInputStream(GZIPInputStream(input)).use { i ->
            require(i.readInt() == MAGIC) { "Not a save file" }
            val version = i.readInt()
            i.readLong()
            val simTime = i.readDouble()
            i.readInt(); i.readInt()
            val seed = i.readLong()
            val f = Factory(World(seed))
            f.simTime = simTime
            f.tickCount = i.readLong(); f.dayTime = i.readFloat()
            val cam = Camera(i.readFloat(), i.readFloat(), i.readFloat())
            val nItems = i.readShort().toInt()
            for (k in 0 until nItems) { val v = i.readInt(); if (k < Item.COUNT) f.stock[k] = v }
            val nTech = i.readShort().toInt()
            for (k in 0 until nTech) {
                val d = i.readBoolean(); val p = i.readInt()
                if (k < Techs.ALL.size) { f.research.done[k] = d; f.research.progress[k] = p }
            }
            val cur = i.readShort().toInt()
            f.research.current = Techs.ALL.getOrNull(cur)
            f.research.chosen = i.readBoolean()
            f.research.reapply()
            val w = f.world
            repeat(i.readInt()) {
                val idx = i.readInt(); val amt = i.readInt()
                w.amount[idx] = amt
                if (amt == 0 && Resource.ALL[w.res[idx].toInt()].solid) w.res[idx] = 0
            }
            w.recountDeposits()
            val ne = i.readInt()
            for (k in 0 until ne) { val e = i.readBoolean(); if (k < w.explored.size) w.explored[k] = e }
            w.exploredVersion++
            val nb = i.readInt()
            val deferredUnderground = ArrayList<UndergroundBelt>()
            repeat(nb) {
                val type = BuildingType.ALL[i.readShort().toInt()]
                val x = i.readShort().toInt(); val y = i.readShort().toInt(); val dir = i.readByte().toInt()
                val len = i.readInt()
                val data = ByteArray(len); i.readFully(data)
                val b = Factory.create(type, x, y, dir)
                DataInputStream(data.inputStream()).use { b.read(it, version) }
                if (b is UndergroundBelt) deferredUnderground.add(b)
                f.insertLoaded(b)
            }
            // Re-pair underground belts now that every tile is known.
            for (u in deferredUnderground) if (u.isExit) u.repair()
            if (f.research.current == null) f.research.autoPick()
            return f to cam
        }
    }
}
