package xyz.xenondevs.nova.ui.menu.item.recipes.group

import de.studiocode.invui.gui.GUI
import de.studiocode.invui.gui.builder.GUIBuilder
import de.studiocode.invui.gui.builder.guitype.GUIType
import de.studiocode.invui.item.Item
import de.studiocode.invui.item.ItemWrapper
import net.md_5.bungee.api.chat.TranslatableComponent
import org.bukkit.Material
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.StonecuttingRecipe
import xyz.xenondevs.nova.data.recipe.ConversionNovaRecipe
import xyz.xenondevs.nova.data.recipe.RecipeContainer
import xyz.xenondevs.nova.data.recipe.RecipeType
import xyz.xenondevs.nova.material.CoreGUIMaterial
import xyz.xenondevs.nova.ui.menu.item.recipes.createRecipeChoiceItem
import xyz.xenondevs.nova.ui.overlay.character.gui.CoreGUITexture
import xyz.xenondevs.nova.util.data.getInputStacks

abstract class ConversionRecipeGroup : RecipeGroup() {
    
    override fun createGUI(container: RecipeContainer): GUI =
        when (container.type) {
            RecipeType.FURNACE -> {
                val recipe = container.recipe as FurnaceRecipe
                createConversionRecipeGUI(recipe.inputChoice, recipe.result, recipe.cookingTime)
            }
            RecipeType.STONECUTTER -> {
                val recipe = container.recipe as StonecuttingRecipe
                createConversionRecipeGUI(recipe.inputChoice, recipe.result, 0)
            }
            else -> {
                val recipe = container.recipe as ConversionNovaRecipe
                createConversionRecipeGUI(recipe.input.getInputStacks(), recipe.result, recipe.time)
            }
        }
    
    private fun createConversionRecipeGUI(input: RecipeChoice, result: ItemStack, time: Int): GUI =
        createConversionRecipeGUI(createRecipeChoiceItem(input), result, time)
    
    private fun createConversionRecipeGUI(input: List<ItemStack>, result: ItemStack, time: Int): GUI =
        createConversionRecipeGUI(createRecipeChoiceItem(input), result, time)
    
    private fun createConversionRecipeGUI(inputUIItem: Item, outputItem: ItemStack, time: Int): GUI {
        val builder = GUIBuilder(GUIType.NORMAL)
            .setStructure(
                ". . t . . . . . .",
                ". . i . . . r . .",
                ". . . . . . . . ."
            )
            .addIngredient('i', inputUIItem)
            .addIngredient('r', createRecipeChoiceItem(listOf(outputItem)))
        
        if (time != 0) {
            builder.addIngredient(
                't', CoreGUIMaterial.TP_STOPWATCH
                .createClientsideItemBuilder()
                .setDisplayName(TranslatableComponent("menu.nova.recipe.time", time / 20.0))
            )
        }
        
        return builder.build()
    }
    
}

internal object SmeltingRecipeGroup : ConversionRecipeGroup() {
    override val priority = 1
    override val icon = ItemWrapper(ItemStack(Material.FURNACE))
    override val texture = CoreGUITexture.RECIPE_SMELTING
}

internal object StonecutterRecipeGroup : ConversionRecipeGroup() {
    override val priority = 2
    override val icon = ItemWrapper(ItemStack(Material.STONECUTTER))
    override val texture = CoreGUITexture.RECIPE_CONVERSION
}
