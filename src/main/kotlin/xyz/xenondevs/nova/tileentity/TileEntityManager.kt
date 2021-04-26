package xyz.xenondevs.nova.tileentity

import com.google.gson.JsonObject
import org.bukkit.*
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import xyz.xenondevs.nova.NOVA
import xyz.xenondevs.nova.material.NovaMaterial
import xyz.xenondevs.nova.serialization.JsonElementDataType
import xyz.xenondevs.nova.util.*
import kotlin.math.roundToInt

val TILE_ENTITY_KEY = NamespacedKey(NOVA, "tileEntity")

fun ItemStack.setTileEntityData(data: JsonObject) {
    if (hasItemMeta()) {
        val itemMeta = this.itemMeta!!
        val dataContainer = itemMeta.persistentDataContainer
        dataContainer.set(TILE_ENTITY_KEY, JsonElementDataType, data)
        this.itemMeta = itemMeta
    }
}

fun ItemStack.getTileEntityData(): JsonObject? {
    if (hasItemMeta()) {
        val dataContainer = itemMeta!!.persistentDataContainer
        if (dataContainer.has(TILE_ENTITY_KEY, JsonElementDataType)) {
            return dataContainer.get(TILE_ENTITY_KEY, JsonElementDataType) as JsonObject
        }
    }
    
    return null
}

fun ItemStack.hasTileEntityData(): Boolean {
    if (hasItemMeta()) {
        val dataContainer = itemMeta!!.persistentDataContainer
        return dataContainer.has(TILE_ENTITY_KEY, JsonElementDataType)
    }
    
    return false
}

fun ArmorStand.setTileEntityData(data: JsonObject) =
    persistentDataContainer.set(TILE_ENTITY_KEY, JsonElementDataType, data)

fun ArmorStand.getTileEntityData() =
    persistentDataContainer.get(TILE_ENTITY_KEY, JsonElementDataType) as JsonObject

fun ArmorStand.hasTileEntityData(): Boolean =
    persistentDataContainer.has(TILE_ENTITY_KEY, JsonElementDataType)

object TileEntityManager : Listener {
    
    private val tileEntityMap = HashMap<Chunk, HashMap<Location, TileEntity>>()
    private val tileEntities: List<TileEntity>
        get() = tileEntityMap.flatMap { (_, chunkMap) -> chunkMap.values }
    
    fun init() {
        Bukkit.getServer().pluginManager.registerEvents(this, NOVA)
        Bukkit.getWorlds().flatMap { it.loadedChunks.asList() }.forEach(this::handleChunkLoad)
        NOVA.disableHandlers += { Bukkit.getWorlds().flatMap { it.loadedChunks.asList() }.forEach(this::handleChunkUnload) }
        runTaskTimer(0, 1) { tileEntities.forEach(TileEntity::handleTick) }
    }
    
    fun placeTileEntity(location: Location, rotation: Float, material: NovaMaterial, data: JsonObject = JsonObject()) {
        val block = location.block
        
        // spawn ArmorStand there
        val headItem = material.block!!.getItem("")
        val spawnLocation = location
            .clone()
            .add(0.5, 0.0, 0.5)
            .also {
                var yaw = rotation % 360
                if (yaw < 0) yaw += 360
                yaw = (yaw / 90).roundToInt() * 90f
                yaw += 180
                it.yaw = yaw
            }
        val armorStand = EntityUtils.spawnArmorStandSilently(
            spawnLocation,
            headItem
        )
        
        // set TileEntity data
        armorStand.setTileEntityData(data.let { JsonObject().apply { add("global", it) } })
        
        // create TileEntity instance
        val tileEntity = material.createTileEntity!!(material, armorStand)
        
        // add to tileEntities map
        val chunk = block.chunk
        val chunkMap = tileEntityMap[chunk] ?: HashMap<Location, TileEntity>().also { tileEntityMap[chunk] = it }
        chunkMap[location] = tileEntity
        
        // 1 tick later or it collides with the cancelled event which removes the block
        runTaskLater(1) {
            if (material.hitbox != null) block.type = material.hitbox
            tileEntity.handleInitialized()
        }
    }
    
    fun destroyTileEntity(tileEntity: TileEntity, dropItems: Boolean) {
        val location = tileEntity.armorStand.location.blockLocation
        val chunk = location.chunk
        
        location.block.type = Material.AIR
        
        val drops = tileEntity.destroy(dropItems) // destroy tileEntity and save drops for later
        
        // remove TileEntity and ArmorStand
        tileEntityMap[chunk]?.remove(location)
        tileEntity.armorStand.remove()
        
        tileEntity.handleRemoved(unload = false)
        
        // drop items a tick later to prevent interference with the cancellation of the break event
        runTaskLater(1) { location.clone().dropItems(drops) }
    }
    
    fun getTileEntityAt(location: Location) = tileEntityMap[location.chunk]?.get(location)
    
    private fun handleChunkLoad(chunk: Chunk) {
        val chunkMap = HashMap<Location, TileEntity>()
        
        chunk.entities
            .filterIsInstance<ArmorStand>()
            .filter { it.hasTileEntityData() }
            .forEach { armorStand ->
                armorStand.fireTicks = Int.MAX_VALUE
                val tileEntity = TileEntity.newInstance(armorStand)
                chunkMap[armorStand.location.clone().apply { removeOrientation() }.subtract(0.5, 0.0, 0.5)] = tileEntity
            }
        
        tileEntityMap[chunk] = chunkMap
        chunkMap.values.forEach(TileEntity::handleInitialized)
    }
    
    private fun handleChunkUnload(chunk: Chunk) {
        val tileEntities = tileEntityMap[chunk]
        tileEntityMap.remove(chunk)
        tileEntities?.forEach { (_, tileEntity) -> tileEntity.handleDisabled(); tileEntity.handleRemoved(unload = true) }
    }
    
    @EventHandler
    fun handleChunkLoad(event: ChunkLoadEvent) {
        handleChunkLoad(event.chunk)
    }
    
    @EventHandler
    fun handleChunkUnload(event: ChunkUnloadEvent) {
        handleChunkUnload(event.chunk)
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun handlePlace(event: BlockPlaceEvent) {
        val player = event.player
        val placedItem = event.itemInHand
        val material = NovaMaterial.toNovaMaterial(placedItem)
        if (material != null) {
            event.isCancelled = true
            if (material.isBlock) {
                val location = event.block.location
                if (getTileEntityAt(location) == null) {
                    val data = if (placedItem.hasTileEntityData()) placedItem.getTileEntityData()!! else JsonObject()
                    placeTileEntity(event.block.location, player.location.yaw, material, data)
                    
                    if (player.gameMode == GameMode.SURVIVAL) placedItem.amount--
                }
            }
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun handleBreak(event: BlockBreakEvent) {
        val tileEntity = getTileEntityAt(event.block.location)
        if (tileEntity != null) {
            event.isCancelled = true
            destroyTileEntity(tileEntity, event.player.gameMode == GameMode.SURVIVAL)
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun handleInteract(event: PlayerInteractEvent) {
        val action = event.action
        if (action == Action.RIGHT_CLICK_BLOCK && !event.player.isSneaking) {
            val block = event.clickedBlock!!
            val tileEntity = getTileEntityAt(block.location)
            tileEntity?.handleRightClick(event)
        }
    }
    
    @EventHandler
    fun handleInventoryCreative(event: InventoryCreativeEvent) {
        val player = event.whoClicked as Player
        val targetBlock = player.getTargetBlockExact(8)
        if (targetBlock != null && targetBlock.type == event.cursor.type) {
            val tileEntity = getTileEntityAt(targetBlock.location)
            if (tileEntity != null) {
                val novaMaterial = tileEntity.material
                event.cursor = novaMaterial.createItemStack()
            }
        }
    }
    
    @EventHandler
    fun handlePistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { getTileEntityAt(it.location) != null }) event.isCancelled = true
    }
    
    @EventHandler
    fun handlePistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { getTileEntityAt(it.location) != null }) event.isCancelled = true
    }
    
}
