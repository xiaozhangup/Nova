package xyz.xenondevs.nova.advancement

import net.roxeez.advancement.Advancement
import org.bukkit.NamespacedKey
import xyz.xenondevs.nova.NOVA
import xyz.xenondevs.nova.material.NovaMaterial

object SolarPanelAdvancement : Advancement(NamespacedKey(NOVA, "solar_panel")) {
    
    init {
        setParent(FurnaceGeneratorAdvancement.key)
        addObtainCriteria(NovaMaterial.SOLAR_PANEL)
        setDisplay {
            it.setTitle("Clean Energy")
            it.setDescription("Craft a solar panel")
            it.setIcon(NovaMaterial.SOLAR_PANEL.toIcon())
        }
    }
    
}