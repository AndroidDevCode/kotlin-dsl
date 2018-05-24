/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.provider

import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.api.internal.initialization.ScriptHandlerInternal
import org.gradle.api.internal.plugins.PluginAwareInternal

import org.gradle.groovy.scripts.ScriptSource

import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.exceptions.LocationAwareException

import org.gradle.kotlin.dsl.execution.UnexpectedBlock
import org.gradle.kotlin.dsl.execution.extractTopLevelSectionFrom
import org.gradle.kotlin.dsl.execution.handleUnexpectedBlock
import org.gradle.kotlin.dsl.execution.linePreservingBlankRanges
import org.gradle.kotlin.dsl.execution.linePreservingSubstring
import org.gradle.kotlin.dsl.execution.linePreservingSubstring_
import org.gradle.kotlin.dsl.execution.locationAwareExceptionHandlingFor

import org.gradle.kotlin.dsl.support.ScriptCompilationException
import org.gradle.kotlin.dsl.support.unsafeLazy

import org.gradle.plugin.management.internal.PluginRequests

import org.gradle.plugin.use.PluginDependenciesSpec
import org.gradle.plugin.use.internal.PluginRequestCollector

import org.gradle.util.TextUtil.normaliseLineSeparators

import kotlin.reflect.KClass


internal
data class PluginsBlockMetadata(val lineNumber: Int)


internal
class KotlinScriptSource(val source: ScriptSource) {

    private
    val scriptResource = source.resource!!

    val scriptPath = source.fileName!!

    val script = normaliseLineSeparators(scriptResource.text!!)

    val displayName: String
        get() = source.displayName

    fun classLoaderScopeIdFor(stage: String) =
        org.gradle.kotlin.dsl.execution.classLoaderScopeIdFor(scriptPath, stage)
}


internal
class KotlinScriptEvaluation(
    private val scriptTarget: KotlinScriptTarget<Any>,
    private val scriptSource: KotlinScriptSource,
    private val scriptHandler: ScriptHandlerInternal,
    private val targetScope: ClassLoaderScope,
    private val baseScope: ClassLoaderScope,
    private val classloadingCache: KotlinScriptClassloadingCache,
    private val kotlinCompiler: CachingKotlinCompiler,
    private val pluginRequestsHandler: PluginRequestsHandler,
    private val classPathProvider: KotlinScriptClassPathProvider,
    private val classPathModeExceptionCollector: ClassPathModeExceptionCollector
) {

    private
    val buildscriptBlockCompilationClassPath: Lazy<ClassPath> = unsafeLazy {
        classPathProvider.compilationClassPathOf(targetScope.parent)
    }

    private
    val pluginsBlockCompilationClassPath: Lazy<ClassPath> = buildscriptBlockCompilationClassPath

    private
    val compilationClassPath: ClassPath by unsafeLazy {
        classPathProvider.compilationClassPathOf(targetScope)
    }

    private
    val accessorsClassPath: ClassPath by unsafeLazy {
        scriptTarget.accessorsClassPathFor(compilationClassPath).bin
    }

    private
    val buildscriptBlockRange: IntRange? by unsafeLazy {
        extractTopLevelSectionFrom(script, scriptTarget.buildscriptBlockName)
    }

    private
    val pluginsBlockRange: IntRange? by unsafeLazy {
        extractTopLevelSectionFrom(script, "plugins")
    }

    fun execute() {
        withUnexpectedBlockHandling {
            prepareForCompilation()
            executeBuildscriptBlock()
            executePluginsBlock()
            executeScriptBody()
        }
    }

    fun executeIgnoringErrors(executeScriptBody: Boolean) {
        ignoringErrors { prepareForCompilation() }
        ignoringErrors { executeBuildscriptBlock() }
        ignoringErrors { executePluginsBlock() }
        if (executeScriptBody) {
            ignoringErrors { executeScriptBody() }
        }
    }

    private
    fun prepareForCompilation() {
        validateExtraSingleOrNoneBlockNames()
        scriptTarget.prepare()
    }

    private
    fun validateExtraSingleOrNoneBlockNames() =
        scriptTarget.extraSingleOrNoneBlockNames.forEach {
            extractTopLevelSectionFrom(script, it)
        }

    private
    fun executeScriptBody() =
        loadScriptBodyClass().eval(scriptSource) {
            scriptTarget.eval(scriptClass)
        }

    private
    fun executeBuildscriptBlock() =
        BuildscriptBlockEvaluator(
            scriptSource,
            scriptTarget,
            buildscriptBlockRange,
            buildscriptBlockCompilationClassPath,
            baseScope,
            kotlinCompiler,
            classloadingCache).evaluate()

    private
    fun executePluginsBlock() {
        prepareTargetClassLoaderScope()
        applyPlugins(pluginRequests())
    }

    private
    fun prepareTargetClassLoaderScope() {
        targetScope.export(classPathProvider.gradleApiExtensions)
    }

    private
    fun pluginRequests() =
        scriptTarget.pluginsBlockTemplate?.let { template ->
            collectPluginRequestsFromPluginsBlock(template)
        }

    private
    fun collectPluginRequestsFromPluginsBlock(scriptTemplate: KClass<*>): PluginRequests {
        val pluginRequestCollector = PluginRequestCollector(scriptSource.source)
        executePluginsBlockOn(pluginRequestCollector, scriptTemplate)
        return pluginRequestCollector.pluginRequests
    }

    private
    fun executePluginsBlockOn(pluginRequestCollector: PluginRequestCollector, scriptTemplate: KClass<*>) =
        pluginsBlockRange?.let { pluginsRange ->
            val loadedPluginsBlockClass = loadPluginsBlockClass(scriptBlockForPlugins(pluginsRange, scriptTemplate))
            executeCompiledPluginsBlockOn(pluginRequestCollector, loadedPluginsBlockClass)
        }

    private
    fun executeCompiledPluginsBlockOn(
        pluginRequestCollector: PluginRequestCollector,
        loadedPluginsBlockClass: LoadedScriptClass<PluginsBlockMetadata>
    ) {

        val pluginDependenciesSpec = pluginRequestCollector.createSpec(loadedPluginsBlockClass.compiledScript.metadata.lineNumber)
        loadedPluginsBlockClass.eval(scriptSource) {
            instantiate(scriptClass, PluginDependenciesSpec::class, pluginDependenciesSpec)
        }
    }

    private
    fun applyPlugins(pluginRequests: PluginRequests?) =
        pluginRequestsHandler.handle(
            pluginRequests, scriptHandler, scriptTarget.`object` as PluginAwareInternal, targetScope)

    private
    fun <T> withKotlinCompiler(action: CachingKotlinCompiler.() -> T) =
        scriptSource.withLocationAwareExceptionHandling {
            kotlinCompiler.action()
        }

    private
    fun scriptBlockForPlugins(pluginsRange: IntRange, scriptTemplate: KClass<*>) =
        script.linePreservingSubstring_(pluginsRange).let { (lineNumber, source) ->
            ScriptBlock(
                "plugins block '$scriptPath'",
                scriptTemplate,
                scriptPath,
                source,
                PluginsBlockMetadata(lineNumber))
        }

    private
    fun loadPluginsBlockClass(scriptBlock: ScriptBlock<PluginsBlockMetadata>) =
        classloadingCache.loadScriptClass(
            scriptBlock,
            baseScope.exportClassLoader,
            ::compilePluginsBlock,
            ::pluginsBlockClassLoaderScope)

    private
    fun compilePluginsBlock(scriptBlock: ScriptBlock<PluginsBlockMetadata>) =
        withKotlinCompiler {
            compileScriptBlock(scriptBlock, pluginsBlockCompilationClassPath.value)
        }

    private
    fun loadScriptBodyClass() =
        classloadingCache.loadScriptClass(
            scriptBlockForBody(),
            targetScope.localClassLoader,
            ::compileScriptBody,
            ::scriptBodyClassLoaderScope,
            accessorsClassPath)

    private
    fun scriptBlockForBody() =
        ScriptBlock(
            scriptSource.displayName,
            scriptTarget.scriptTemplate,
            scriptPath,
            scriptWithoutBuildscriptAndPluginsBlocks,
            Unit)

    private
    val scriptWithoutBuildscriptAndPluginsBlocks
        get() = script.linePreservingBlankRanges(listOfNotNull(buildscriptBlockRange, pluginsBlockRange))

    private
    fun compileScriptBody(scriptBlock: ScriptBlock<Unit>) =
        withKotlinCompiler {
            compileScriptBlock(scriptBlock, compilationClassPath + accessorsClassPath)
        }

    private
    fun scriptBodyClassLoaderScope() = scriptClassLoaderScopeWith(accessorsClassPath)

    private
    fun scriptClassLoaderScopeWith(accessorsClassPath: ClassPath) =
        targetScope
            .createChild(classLoaderScopeIdFor("script"))
            .local(accessorsClassPath)

    private
    fun pluginsBlockClassLoaderScope() =
        baseScopeFor("plugins")

    private
    fun baseScopeFor(stage: String) =
        baseScope.createChild(classLoaderScopeIdFor(stage))

    private
    fun classLoaderScopeIdFor(stage: String) =
        scriptSource.classLoaderScopeIdFor(stage)

    private
    inline fun ignoringErrors(action: () -> Unit) = classPathModeExceptionCollector.ignoringErrors(action)

    private
    fun <T : Any> instantiate(scriptClass: Class<*>, targetType: KClass<*>, target: T) {
        scriptClass.getConstructor(targetType.java).newInstance(target)
    }

    private
    inline fun withUnexpectedBlockHandling(action: () -> Unit) {
        try {
            action()
        } catch (unexpectedBlock: UnexpectedBlock) {
            handleUnexpectedBlock(unexpectedBlock, script, scriptPath)
        }
    }

    private
    val scriptPath
        get() = scriptSource.scriptPath

    private
    val script
        get() = scriptSource.script
}


private
class BuildscriptBlockEvaluator(
    val scriptSource: KotlinScriptSource,
    val scriptTarget: KotlinScriptTarget<Any>,
    val buildscriptBlockRange: IntRange?,
    val classPath: Lazy<ClassPath>,
    val baseScope: ClassLoaderScope,
    val kotlinCompiler: CachingKotlinCompiler,
    val classloadingCache: KotlinScriptClassloadingCache
) {

    fun evaluate() {
        buildscriptBlockRange?.let { buildscriptRange ->
            executeBuildscriptBlockFrom(buildscriptRange, scriptTarget.buildscriptBlockTemplate)
        }
    }

    private
    fun compileBuildscriptBlock(scriptBlock: ScriptBlock<Unit>) =
        withKotlinCompiler {
            compileScriptBlock(scriptBlock, classPath.value)
        }

    private
    fun buildscriptBlockClassLoaderScope() =
        baseScopeFor("buildscript")

    private
    fun baseScopeFor(stage: String) =
        baseScope.createChild(classLoaderScopeIdFor(stage))

    private
    fun classLoaderScopeIdFor(stage: String) =
        scriptSource.classLoaderScopeIdFor(stage)

    private
    fun loadBuildscriptBlockClass(scriptBlock: ScriptBlock<Unit>) =
        classloadingCache.loadScriptClass(
            scriptBlock,
            baseScope.exportClassLoader,
            ::compileBuildscriptBlock,
            ::buildscriptBlockClassLoaderScope)

    private
    fun executeBuildscriptBlockFrom(buildscriptRange: IntRange, scriptTemplate: KClass<*>) =
        loadBuildscriptBlockClass(scriptBlockForBuildscript(buildscriptRange, scriptTemplate))
            .eval(scriptSource) {
                scriptTarget.eval(scriptClass)
            }

    private
    fun scriptBlockForBuildscript(buildscriptRange: IntRange, scriptTemplate: KClass<*>) =
        ScriptBlock(
            "$buildscriptBlockName block '$scriptPath'",
            scriptTemplate,
            scriptPath,
            script.linePreservingSubstring(buildscriptRange),
            Unit)

    private
    fun <T> withKotlinCompiler(action: CachingKotlinCompiler.() -> T): T =
        scriptSource.withLocationAwareExceptionHandling {
            action(kotlinCompiler)
        }

    private
    val scriptPath
        get() = scriptSource.scriptPath

    private
    val script
        get() = scriptSource.script

    private
    val buildscriptBlockName
        get() = scriptTarget.buildscriptBlockName
}


private
inline fun ClassPathModeExceptionCollector.ignoringErrors(action: () -> Unit) =
    try {
        action()
    } catch (e: Exception) {
        e.printStackTrace()
        collect(e)
    }


private
inline fun <T> KotlinScriptSource.withLocationAwareExceptionHandling(action: () -> T): T =
    try {
        action()
    } catch (e: ScriptCompilationException) {
        throw LocationAwareException(e, source, e.firstErrorLine)
    }


private
inline fun <T> LoadedScriptClass<T>.eval(scriptSource: KotlinScriptSource, action: LoadedScriptClass<T>.() -> Unit) =
    eval(scriptClass.classLoader, scriptSource.source, action)


private
inline fun <T> LoadedScriptClass<T>.eval(classLoader: ClassLoader, scriptSource: ScriptSource, action: LoadedScriptClass<T>.() -> Unit) =
    withContextClassLoader(classLoader) {
        withLocationAwareExceptionHandling(scriptSource, action)
    }


private
inline fun <T> LoadedScriptClass<T>.withLocationAwareExceptionHandling(
    scriptSource: ScriptSource,
    action: LoadedScriptClass<T>.() -> Unit
) =
    try {
        action()
    } catch (e: Throwable) {
        locationAwareExceptionHandlingFor(e, scriptClass, scriptSource)
    }


private
inline fun withContextClassLoader(classLoader: ClassLoader, block: () -> Unit) {
    val currentThread = Thread.currentThread()
    val previous = currentThread.contextClassLoader
    try {
        currentThread.contextClassLoader = classLoader
        block()
    } finally {
        currentThread.contextClassLoader = previous
    }
}
