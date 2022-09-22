package xyz.xenondevs.nova.transformer

import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap
import xyz.xenondevs.bytebase.INSTRUMENTATION
import xyz.xenondevs.bytebase.jvm.VirtualClassPath
import xyz.xenondevs.nova.LOGGER
import xyz.xenondevs.nova.NOVA
import xyz.xenondevs.nova.Nova
import xyz.xenondevs.nova.data.config.DEFAULT_CONFIG
import xyz.xenondevs.nova.data.config.NovaConfig
import xyz.xenondevs.nova.initialize.Initializable
import xyz.xenondevs.nova.initialize.InitializationStage
import xyz.xenondevs.nova.transformer.patch.AnvilRenamingPatch
import xyz.xenondevs.nova.transformer.patch.FieldFilterPatch
import xyz.xenondevs.nova.transformer.patch.ToolPatches
import xyz.xenondevs.nova.transformer.patch.noteblock.NoteBlockPatch
import xyz.xenondevs.nova.util.reflection.ReflectionUtils
import java.lang.instrument.ClassDefinition
import java.lang.reflect.Field

internal object Patcher : Initializable() {
    
    override val initializationStage = InitializationStage.PRE_WORLD
    override val dependsOn = emptySet<Initializable>()
    
    private val extraOpens = setOf("java.lang", "java.util", "jdk.internal.misc", "jdk.internal.reflect")
    private val transformers by lazy {
        sequenceOf(NoteBlockPatch, FieldFilterPatch, ToolPatches, AnvilRenamingPatch)
            .filter(Transformer::shouldTransform).toSet()
    }
    
    override fun init() {
        if (!DEFAULT_CONFIG.getBoolean("use_agent"))
            return
        
        if (runCatching { INSTRUMENTATION }.isFailure) {
            LOGGER.warning("Java agents aren't supported on this server! Disabling...")
            DEFAULT_CONFIG["use_agent"] = false
            NovaConfig.save("config")
            return
        }
        
        LOGGER.info("Applying patches...")
        VirtualClassPath.classLoaders += NOVA.loader.javaClass.classLoader.parent
        redefineModule()
        runTransformers()
        insertPatchedLoader()
    }
    
    override fun disable() {
        if (DEFAULT_CONFIG.getBoolean("use_agent"))
            removePatchedLoader()
    }
    
    private fun redefineModule() {
        val myModule = setOf(Nova::class.java.module)
        INSTRUMENTATION.redefineModule(
            Field::class.java.module, // java.base module
            emptySet(),
            emptyMap(),
            extraOpens.associateWith { myModule },
            emptySet(),
            emptyMap()
        )
    }
    
    private fun runTransformers() {
        val classes = Object2BooleanOpenHashMap<Class<*>>() // class -> computeFrames
        transformers.forEach { transformer ->
            transformer.classes.forEach { clazz ->
                if (transformer.computeFrames)
                    classes[clazz.java] = true
                else classes.putIfAbsent(clazz.java, false)
            }
        }
        transformers.forEach(Transformer::transform)
        val definitions = classes.map { (clazz, computeFrames) ->
            ClassDefinition(clazz, VirtualClassPath[clazz].assemble(computeFrames))
        }.toTypedArray()
        INSTRUMENTATION.redefineClasses(*definitions)
    }
    
    private fun insertPatchedLoader() {
        val spigotLoader = NOVA.loader.javaClass.classLoader.parent
        val parentField = ReflectionUtils.getField(ClassLoader::class.java, true, "parent")
        ReflectionUtils.setFinalField(parentField, spigotLoader, PatchedClassLoader())
    }
    
    private fun removePatchedLoader() {
        val spigotLoader = NOVA.loader.javaClass.classLoader.parent
        val parentField = ReflectionUtils.getField(ClassLoader::class.java, true, "parent")
        ReflectionUtils.setFinalField(parentField, spigotLoader, spigotLoader.parent.parent)
    }
    
}