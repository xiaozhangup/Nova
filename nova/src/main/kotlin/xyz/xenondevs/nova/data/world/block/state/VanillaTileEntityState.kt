package xyz.xenondevs.nova.data.world.block.state

import xyz.xenondevs.cbf.CBF
import xyz.xenondevs.cbf.Compound
import xyz.xenondevs.cbf.buffer.ByteBuffer
import xyz.xenondevs.nova.data.NamespacedId
import xyz.xenondevs.nova.data.world.WorldDataManager
import xyz.xenondevs.nova.tileentity.vanilla.VanillaTileEntity
import xyz.xenondevs.nova.tileentity.vanilla.VanillaTileEntityManager
import xyz.xenondevs.nova.world.BlockPos

internal class VanillaTileEntityState(override val pos: BlockPos, override val id: NamespacedId) : BlockState {
    
    @Volatile
    lateinit var data: Compound
    
    @Volatile
    lateinit var tileEntity: VanillaTileEntity
    
    @Volatile
    override var isLoaded = false
        private set
    
    constructor(pos: BlockPos, id: String) : this(pos, NamespacedId.of(id))
    
    override fun handleInitialized(placed: Boolean) {
        if (!::data.isInitialized) data = Compound()
        val tileEntity = VanillaTileEntity.of(this)
        if (tileEntity != null) {
            this.tileEntity = tileEntity
            tileEntity.handleInitialized()
            VanillaTileEntityManager.registerTileEntity(this)
            
            isLoaded = true
        } else {
            WorldDataManager.removeBlockState(pos)
        }
    }
    
    override fun handleRemoved(broken: Boolean) {
        isLoaded = false
        
        if (::tileEntity.isInitialized) {
            tileEntity.handleRemoved(!broken)
            VanillaTileEntityManager.unregisterTileEntity(this)
        }
    }
    
    override fun read(buf: ByteBuffer) {
        data = CBF.read(buf)!!
    }
    
    override fun write(buf: ByteBuffer) {
        if (::tileEntity.isInitialized) {
            tileEntity.saveData()
        }
        CBF.write(data, buf)
    }
    
    override fun toString(): String {
        return "VanillaTileEntityState(pos=$pos, id=$id, data=$data)"
    }
    
}