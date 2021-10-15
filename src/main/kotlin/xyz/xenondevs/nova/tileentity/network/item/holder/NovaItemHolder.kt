package xyz.xenondevs.nova.tileentity.network.item.holder

import de.studiocode.invui.virtualinventory.VirtualInventory
import org.bukkit.block.BlockFace
import xyz.xenondevs.nova.data.serialization.cbf.element.CompoundElement
import xyz.xenondevs.nova.tileentity.NetworkedTileEntity
import xyz.xenondevs.nova.tileentity.network.item.ItemConnectionType
import xyz.xenondevs.nova.tileentity.network.item.ItemFilter
import xyz.xenondevs.nova.tileentity.network.item.inventory.NetworkedInventory
import xyz.xenondevs.nova.tileentity.network.item.inventory.NetworkedVirtualInventory
import xyz.xenondevs.nova.util.CUBE_FACES
import xyz.xenondevs.nova.util.associateWithToEnumMap
import xyz.xenondevs.nova.util.emptyEnumMap
import xyz.xenondevs.nova.util.enumMapOf
import java.util.*

fun NovaItemHolder(
    endPoint: NetworkedTileEntity,
    defaultInventory: Pair<VirtualInventory, ItemConnectionType>,
    vararg otherInventories: Pair<VirtualInventory, ItemConnectionType>,
    lazyDefaultInvConfig: (() -> MutableMap<BlockFace, UUID>) = { CUBE_FACES.associateWithToEnumMap { defaultInventory.first.uuid } },
    lazyDefaultTypeConfig: (() -> MutableMap<BlockFace, ItemConnectionType>)? = null
): NovaItemHolder {
    val virtualInventories = arrayListOf(defaultInventory).apply { addAll(otherInventories) }
    val networkedInventories: Map<VirtualInventory, NetworkedInventory> = virtualInventories.associate { (vi, _) -> vi to NetworkedVirtualInventory(vi) }
    val availableInventories: MutableMap<UUID, NetworkedInventory> = virtualInventories.associateTo(HashMap()) { (vi, _) -> vi.uuid to networkedInventories[vi]!! }
    val allowedConnectionTypes: Map<NetworkedInventory, ItemConnectionType> = virtualInventories.associate { (vi, pc) -> networkedInventories[vi]!! to pc }
    
    return NovaItemHolder(
        endPoint,
        availableInventories,
        allowedConnectionTypes,
        lazyDefaultInvConfig,
        lazyDefaultTypeConfig
    )
}

@JvmName("NovaItemHolder1")
fun NovaItemHolder(
    endPoint: NetworkedTileEntity,
    defaultInventory: Pair<UUID, Pair<NetworkedInventory, ItemConnectionType>>,
    vararg otherInventories: Pair<UUID, Pair<NetworkedInventory, ItemConnectionType>>,
    lazyDefaultInvConfig: (() -> MutableMap<BlockFace, UUID>) = { CUBE_FACES.associateWithToEnumMap { defaultInventory.first } },
    lazyDefaultTypeConfig: (() -> MutableMap<BlockFace, ItemConnectionType>)? = null
): NovaItemHolder {
    val allInventories = hashMapOf(defaultInventory).apply { putAll(otherInventories) }
    val networkedInventories = allInventories.mapValuesTo(HashMap()) { (_, pair) -> pair.first }
    val allowedConnectionTypes = allInventories.entries.associate { (_, pair) -> pair }
    
    return NovaItemHolder(
        endPoint,
        networkedInventories,
        allowedConnectionTypes,
        lazyDefaultInvConfig,
        lazyDefaultTypeConfig
    )
}

class NovaItemHolder(
    override val endPoint: NetworkedTileEntity,
    private val availableInventories: MutableMap<UUID, out NetworkedInventory>,
    override val allowedConnectionTypes: Map<NetworkedInventory, ItemConnectionType>,
    lazyDefaultInvConfig: () -> MutableMap<BlockFace, UUID>,
    lazyDefaultTypeConfig: (() -> MutableMap<BlockFace, ItemConnectionType>)?
) : ItemHolder {
    
    override val inventories: MutableMap<BlockFace, NetworkedInventory> =
        (endPoint.retrieveEnumMapOrNull("inventories") ?: lazyDefaultInvConfig())
            .mapValuesTo(enumMapOf()) { availableInventories[it.value]!! }
    
    override val itemConfig: MutableMap<BlockFace, ItemConnectionType> =
        endPoint.retrieveDoubleEnumMap("itemConfig", lazyDefaultTypeConfig
            ?: { CUBE_FACES.associateWithToEnumMap { ItemConnectionType.NONE } })
    
    override val insertFilters: MutableMap<BlockFace, ItemFilter> =
        endPoint.retrieveEnumMap<BlockFace, CompoundElement>("insertFilters") { emptyEnumMap() }
            .mapValuesTo(emptyEnumMap()) { ItemFilter(it.value) }
    
    override val extractFilters: MutableMap<BlockFace, ItemFilter> =
        endPoint.retrieveEnumMap<BlockFace, CompoundElement>("extractFilters") { emptyEnumMap() }
            .mapValuesTo(emptyEnumMap()) { ItemFilter(it.value) }
    
    override val insertPriorities: MutableMap<BlockFace, Int> =
        endPoint.retrieveEnumMap("insertPriorities") { CUBE_FACES.associateWithToEnumMap { 50 } }
    
    override val extractPriorities: MutableMap<BlockFace, Int> =
        endPoint.retrieveEnumMap("extractPriorities") { CUBE_FACES.associateWithToEnumMap { 50 } }
    
    override val channels: MutableMap<BlockFace, Int> =
        endPoint.retrieveEnumMap("channels") { CUBE_FACES.associateWithToEnumMap { 0 } }
    
    fun getNetworkedInventory(virtualInventory: VirtualInventory) =
        availableInventories[virtualInventory.uuid]!!
    
    fun getNetworkedInventory(uuid: UUID) =
        availableInventories[uuid]!!
    
    private fun findUUID(networkedInventory: NetworkedInventory) =
        availableInventories.firstNotNullOfOrNull { if (it.value == networkedInventory) it.key else null }
    
    override fun saveData() {
        endPoint.storeEnumMap("itemConfig", itemConfig)
        endPoint.storeEnumMap("insertFilters", insertFilters) { it.compound }
        endPoint.storeEnumMap("extractFilters", extractFilters) { it.compound }
        endPoint.storeEnumMap("channels", channels)
        endPoint.storeEnumMap("insertPriorities", insertPriorities)
        endPoint.storeEnumMap("extractPriorities", extractPriorities)
        
        if (availableInventories.isNotEmpty())
            endPoint.storeEnumMap("inventories", inventories.mapValues { findUUID(it.value) })
    }
    
}