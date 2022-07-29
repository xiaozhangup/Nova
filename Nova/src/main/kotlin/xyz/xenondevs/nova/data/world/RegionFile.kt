package xyz.xenondevs.nova.data.world

import org.bukkit.World
import xyz.xenondevs.cbf.CBF
import xyz.xenondevs.cbf.adapter.NettyBufferProvider
import xyz.xenondevs.nova.LOGGER
import xyz.xenondevs.nova.data.config.DEFAULT_CONFIG
import xyz.xenondevs.nova.data.config.configReloadable
import xyz.xenondevs.nova.util.data.readVarInt
import xyz.xenondevs.nova.util.data.use
import xyz.xenondevs.nova.util.data.writeVarInt
import xyz.xenondevs.nova.util.getOrSet
import xyz.xenondevs.nova.world.ChunkPos
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

private const val FILE_VERSION = 1
private val CREATE_BACKUPS by configReloadable { DEFAULT_CONFIG.getBoolean("performance.region_backups") }

/**
 * A binary region file.
 *
 * Format:
 *
 * ```
 * RegionFile {
 *   int8   version;
 *   chunks[n]; {
 *     int16    packed_world_pos
 *     data[m]; {
 *       int16       packed_rel_pos;
 *       string      type
 *       block_state state
 *     }
 *   }
 * }
 * ```
 */
internal class RegionFile(val world: World, val file: File, val regionX: Int, val regionZ: Int) {
    
    val chunks = arrayOfNulls<RegionChunk?>(1024)
    private val backupFile = File(file.parentFile, file.name + ".backup")
    
    init {
        if (backupFile.exists()) {
            LOGGER.warning("Restoring region file $file from backup $backupFile")
            val ins = DataInputStream(backupFile.inputStream())
            val out = file.outputStream()
            
            use(ins, out) {
                val length = ins.readLong()
                if (length == backupFile.length() - 8) {
                    ins.copyTo(out)
                } else LOGGER.warning("Backup file $backupFile is corrupted")
            }
            backupFile.delete()
        }
    }
    
    fun init() {
        if (file.length() != 0L) {
            readFile(DataInputStream(file.inputStream()))
        }
    }
    
    private fun readFile(dis: DataInputStream) {
        if (dis.readByte() != FILE_VERSION.toByte())
            throw IllegalStateException(file.absolutePath + " is not a valid region file")
        while (dis.readByte() == 1.toByte()) {
            val packedPos = dis.readUnsignedShort()
            val chunk = RegionChunk(
                regionX,
                regionZ,
                world = world,
                relChunkX = packedPos shr 5,
                relChunkZ = packedPos and 0x1F,
            )
            val bytes = ByteArray(dis.readVarInt())
            dis.readFully(bytes)
            chunk.read(NettyBufferProvider.wrappedBuffer(bytes))
            chunks[packedPos] = chunk
        }
    }
    
    fun getChunk(pos: ChunkPos): RegionChunk {
        val dx = pos.x and 0x1F
        val dz = pos.z and 0x1F
        val packedCoords = dx shl 5 or dz
        return chunks.getOrSet(packedCoords) { RegionChunk(regionX, regionZ, world, dx, dz) }
    }
    
    fun save() {
        if (CREATE_BACKUPS && file.exists()) {
            val ins = file.inputStream()
            val out = DataOutputStream(backupFile.outputStream())
            use(ins, out) {
                out.writeLong(file.length())
                ins.copyTo(out)
            }
        }
        
        DataOutputStream(file.outputStream()).use { dos ->
            dos.writeByte(FILE_VERSION)
            chunks.asSequence().filterNotNull().filterNot(RegionChunk::isEmpty).forEach {
                dos.writeByte(1)
                dos.writeShort(it.packedCoords)
                val buffer = CBF.buffer()
                it.write(buffer)
                val bytes = buffer.toByteArray()
                dos.writeVarInt(bytes.size)
                dos.write(bytes)
                dos.flush()
            }
            dos.writeByte(0)
        }
    
        if (CREATE_BACKUPS && backupFile.exists())
            backupFile.delete()
    }
    
}