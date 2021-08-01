package xyz.xenondevs.nova.tileentity

import com.google.common.base.Preconditions
import com.google.gson.JsonObject
import de.studiocode.invui.gui.GUI
import de.studiocode.invui.virtualinventory.VirtualInventory
import de.studiocode.invui.virtualinventory.VirtualInventoryManager
import de.studiocode.invui.virtualinventory.event.ItemUpdateEvent
import de.studiocode.invui.virtualinventory.event.UpdateReason
import de.studiocode.invui.window.impl.single.SimpleWindow
import net.md_5.bungee.api.chat.TranslatableComponent
import net.minecraft.world.entity.EquipmentSlot
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import xyz.xenondevs.nova.armorstand.FakeArmorStand
import xyz.xenondevs.nova.database.table.TileEntitiesTable
import xyz.xenondevs.nova.material.NovaMaterial
import xyz.xenondevs.nova.network.energy.EnergyConnectionType
import xyz.xenondevs.nova.network.item.ItemConnectionType
import xyz.xenondevs.nova.region.Region
import xyz.xenondevs.nova.util.*
import java.util.*

internal val SELF_UPDATE_REASON = object : UpdateReason {}

abstract class TileEntity(
    val uuid: UUID,
    val data: JsonObject,
    val material: NovaMaterial,
    val ownerUUID: UUID,
    val armorStand: FakeArmorStand,
) {
    
    protected abstract val gui: TileEntityGUI?
    
    val globalData: JsonObject = data.get("global") as JsonObject
    
    var isValid: Boolean = true
        private set
    
    val location = armorStand.location.blockLocation
    val world = location.world!!
    val chunk = location.chunk
    val facing = armorStand.location.facing
    
    private val inventories = ArrayList<VirtualInventory>()
    val multiModels = ArrayList<MultiModel>()
    
    val additionalHitboxes = HashSet<Location>()
    
    init {
        if (!data.has("material"))
            storeData("material", material)
        if (!data.has("owner"))
            storeData("owner", ownerUUID)
    }
    
    /**
     * Called when the TileEntity is being broken.
     *
     * @param dropItems If items should be dropped. Can be ignored for reasons like
     * dropping the contents of a chest event when the player is in creative mode.
     *
     * @return A list of [ItemStack]s that should be dropped.
     */
    open fun destroy(dropItems: Boolean): ArrayList<ItemStack> {
        val drops = ArrayList<ItemStack>()
        if (dropItems) {
            saveData()
            val item = material.createItemBuilder(this).build()
            if (globalData.entrySet().isNotEmpty()) item.setTileEntityData(globalData)
            drops += item
        }
        
        // inventory drops ignore the dropItems parameter
        inventories.forEach {
            drops += it.items.filterNotNull()
            VirtualInventoryManager.getInstance().remove(it)
        }
        
        return drops
    }
    
    /**
     * Called to save all data using the [storeData] method.
     */
    open fun saveData() {
    }
    
    /**
     * Calls the [saveData] function and then writes the [data] object
     * to the [armor stand][armorStand] of this [TileEntity].
     */
    fun saveAndWriteData() {
        saveData()
        
        // TODO: async?
        transaction {
            TileEntitiesTable.update({ TileEntitiesTable.uuid eq uuid }) {
                it[data] = GSON.toJson(this@TileEntity.data)
            }
        }
    }
    
    /**
     * Called to get the [ItemStack] to be placed as the head of the [FakeArmorStand].
     */
    open fun getHeadStack(): ItemStack {
        return material.block!!.getItem()
    }
    
    /**
     * Calls the [getHeadStack] function and puts the result on the [FakeArmorStand].
     */
    fun updateHeadStack() {
        armorStand.setEquipment(EquipmentSlot.HEAD, getHeadStack())
        armorStand.updateEquipment()
    }
    
    /**
     * Called every tick for every TileEntity that is in a loaded chunk.
     */
    abstract fun handleTick()
    
    /**
     * Called after the TileEntity has been initialized and added to the
     * TileEntity map in the TileEntityManager.
     *
     * The [first] parameter specifies if it is the first time this
     * [TileEntity] got initialized, meaning it has just been placed.
     */
    abstract fun handleInitialized(first: Boolean)
    
    /**
     * Called after the TileEntity has been removed from the
     * TileEntityManager's TileEntity map because it either got
     * unloaded or destroyed.
     *
     * @param unload If the [TileEntity] was removed because the chunk got unloaded.
     */
    open fun handleRemoved(unload: Boolean) {
        isValid = false
        gui?.closeWindows()
        multiModels.forEach { it.removeAllModels() }
        
        if (unload) saveAndWriteData()
    }
    
    /**
     * Called when a player right-clicks the TileEntity.
     * The event has should probably be cancelled if any action
     * is performed in that method.
     */
    open fun handleRightClick(event: PlayerInteractEvent) {
        if (gui != null) {
            event.isCancelled = true
            gui!!.openWindow(event.player)
        }
    }
    
    /**
     * Gets a [VirtualInventory] for this [TileEntity].
     * When [dropItems] is true, the [VirtualInventory] will automatically be
     * deleted and its contents dropped when the [TileEntity] is destroyed.
     */
    fun getInventory(
        salt: String,
        size: Int,
        dropItems: Boolean,
        stackSizes: IntArray,
        itemHandler: (ItemUpdateEvent) -> Unit
    ): VirtualInventory {
        val inventory = VirtualInventoryManager
            .getInstance()
            .getOrCreate(uuid.salt(salt), size, arrayOfNulls(size), stackSizes)
        inventory.setItemUpdateHandler(itemHandler)
        if (dropItems) inventories += inventory
        return inventory
    }
    
    /**
     * Gets a [VirtualInventory] for this [TileEntity].
     * When [dropItems] is true, the [VirtualInventory] will automatically be
     * deleted and its contents dropped when the [TileEntity] is destroyed.
     */
    fun getInventory(salt: String, size: Int, dropItems: Boolean, itemHandler: (ItemUpdateEvent) -> Unit) =
        getInventory(salt, size, dropItems, IntArray(size) { 64 }, itemHandler)
    
    /**
     * Creates a new [MultiModel] for this [TileEntity].
     * When the [TileEntity] is removed, all [Model]s belonging
     * to this [MultiModel] will be removed.
     */
    fun createMultiModel(): MultiModel {
        return MultiModel().also(multiModels::add)
    }
    
    /**
     * Gets the correct direction a block side.
     */
    fun getFace(blockSide: BlockSide) = blockSide.getBlockFace(armorStand.location.yaw)
    
    /**
     * Creates an energy side config
     */
    fun createEnergySideConfig(
        default: EnergyConnectionType,
        vararg blocked: BlockSide
    ): EnumMap<BlockFace, EnergyConnectionType> {
        
        val blockedFaces = blocked.map { getFace(it) }
        return CUBE_FACES.associateWithTo(enumMapOf()) {
            if (blockedFaces.contains(it)) EnergyConnectionType.NONE else default
        }
    }
    
    /**
     * Creates an item side config
     */
    fun createItemSideConfig(
        default: ItemConnectionType,
        vararg blocked: BlockSide
    ): EnumMap<BlockFace, ItemConnectionType> {
        
        val sideConfig = EnumMap<BlockFace, ItemConnectionType>(BlockFace::class.java)
        val blockedFaces = blocked.map { getFace(it) }
        CUBE_FACES.forEach { sideConfig[it] = if (blockedFaces.contains(it)) ItemConnectionType.NONE else default }
        
        return sideConfig
    }
    
    /**
     * Creates a [Pair] of [Locations][Location] which mark the edge points of an area with the
     * given [length], [width], [height] and [vertical translation][translateVertical] in front
     * of this [TileEntity].
     */
    fun getFrontArea(length: Double, width: Double, height: Double, translateVertical: Double): Region {
        val frontFace = getFace(BlockSide.FRONT)
        val startLocation = location.clone().center().advance(frontFace, 0.5)
        
        val pos1 = startLocation.clone().apply {
            advance(getFace(BlockSide.LEFT), width / 2.0)
            y += translateVertical
        }
        
        val pos2 = startLocation.clone().apply {
            advance(getFace(BlockSide.RIGHT), width / 2.0)
            advance(frontFace, length)
            y += height + translateVertical
        }
        
        return Region(LocationUtils.sort(pos1, pos2))
    }
    
    /**
     * Places additional hitboxes for this [TileEntity] and registers them
     * in the [TileEntityManager].
     */
    fun setAdditionalHitboxes(placeBlocks: Boolean, hitboxes: List<Location>) {
        if (placeBlocks) hitboxes.forEach { it.block.type = material.hitbox!! }
        
        additionalHitboxes += hitboxes
        TileEntityManager.addTileEntityLocations(this, hitboxes)
    }
    
    /**
     * Retrieves data using GSON deserialization from the
     * ArmorStand of this TileEntity.
     * If it can't find anything under the given key, the
     * result of the [getAlternative] lambda is returned.
     */
    inline fun <reified T> retrieveData(key: String, getAlternative: () -> T): T {
        return retrieveOrNull(key) ?: getAlternative()
    }
    
    /**
     * Retrieves data using GSON deserialization from the
     * ArmorStand of this TileEntity.
     */
    inline fun <reified T> retrieveOrNull(key: String): T? {
        return GSON.fromJson<T>(data.get(key) ?: globalData.get(key))
    }
    
    /**
     * Serializes objects using GSON and stores them under the given key in
     * the data object. This method does not store any data in the armor stand yet.
     *
     * @param global If the data should also be stored in the [ItemStack]
     * of this [TileEntity].
     */
    fun storeData(key: String, value: Any?, global: Boolean = false) {
        if (global) {
            Preconditions.checkArgument(!data.has(key), "$key is already a non-global value")
            if (value != null) globalData.add(key, GSON.toJsonTree(value))
            else globalData.remove(key)
        } else {
            Preconditions.checkArgument(!globalData.has(key), "$key is already a global value")
            if (value != null) data.add(key, GSON.toJsonTree(value))
            else data.remove(key)
        }
    }
    
    override fun equals(other: Any?): Boolean {
        return other is TileEntity && other === this
    }
    
    override fun hashCode(): Int {
        return uuid.hashCode()
    }
    
    override fun toString(): String {
        return "${javaClass.name}(Material: $material, Location: ${armorStand.location.blockLocation}, UUID: $uuid)"
    }
    
    companion object {
        
        fun create(
            uuid: UUID,
            armorStandLocation: Location,
            data: JsonObject,
            ownerUUID: UUID = GSON.fromJson(data.get("owner"))!!,
            material: NovaMaterial = GSON.fromJson(data.get("material"))!!
        ): TileEntity {
            // create the fake armor stand
            val armorStand = FakeArmorStand(armorStandLocation, false) {
                it.isInvisible = true
                it.isMarker = true
                it.hasVisualFire = material.hitbox.requiresLight
            }
            
            // create the tile entity
            val tileEntity = material.createTileEntity!!(uuid, data, material, ownerUUID, armorStand)
            
            // set the head stack and register
            armorStand.setEquipment(EquipmentSlot.HEAD, tileEntity.getHeadStack())
            armorStand.register()
            
            return tileEntity
        }
        
    }
    
}

abstract class TileEntityGUI(private val title: String) {
    
    abstract val gui: GUI
    
    fun openWindow(player: Player) = SimpleWindow(player, arrayOf(TranslatableComponent(title)), gui).show()
    
    fun closeWindows() = gui.closeForAllViewers()
    
}