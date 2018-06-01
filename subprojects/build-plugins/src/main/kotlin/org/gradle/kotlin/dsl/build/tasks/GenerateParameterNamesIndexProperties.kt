/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.kotlin.dsl.build.tasks

import com.thoughtworks.qdox.JavaProjectBuilder
import com.thoughtworks.qdox.library.SortedClassLibraryBuilder
import com.thoughtworks.qdox.model.JavaMethod

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import org.gradle.internal.classloader.ClassLoaderFactory
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.internal.util.PropertiesUtils.store

import org.gradle.kotlin.dsl.support.serviceOf

import java.util.*


/**
 * Generates parameter names index from sources.
 */
@CacheableTask
@Suppress("MemberVisibilityCanBePrivate")
open class GenerateParameterNamesIndexProperties : DefaultTask() {

    init {
        description = "Generates parameter names index from sources"
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    val sources = project.files()

    @InputFiles
    @Classpath
    val classpath = project.files()

    @OutputFile
    @PathSensitive(PathSensitivity.NONE)
    val outputFile = project.layout.fileProperty()

    @TaskAction
    @Suppress("unused")
    fun generateParameterNamesIndex() {

        val qdoxBuilder = JavaProjectBuilder(sortedClassLibraryBuilderWithClassLoaderFor(classpath))
        val qdoxSources = sources.asSequence().mapNotNull { qdoxBuilder.addSource(it) }

        val properties = qdoxSources
            .flatMap { it.classes.asSequence().filter { it.isPublic } }
            .flatMap { it.methods.asSequence().filter { it.isPublic && it.parameterTypes.isNotEmpty() } }
            .map { method ->
                fullyQualifiedSignatureOf(method) to commaSeparatedParameterNamesOf(method)
            }.toMap(linkedMapOf())

        write(properties)
    }

    private
    fun write(properties: Map<String, String>) {
        outputFile.get().asFile.let { file ->
            file.parentFile.mkdirs()
            store(Properties().apply { properties.forEach { k, v -> setProperty(k, v) } }, file)
        }
    }

    private
    fun fullyQualifiedSignatureOf(method: JavaMethod): String =
        "${method.declaringClass.binaryName}.${method.name}(${signatureOf(method)})"

    private
    fun signatureOf(method: JavaMethod): String =
        method.parameters.joinToString(separator = ",") { p ->
            if (p.isVarArgs || p.javaClass.isArray) "${p.type.binaryName}[]"
            else p.type.binaryName
        }

    private
    fun commaSeparatedParameterNamesOf(method: JavaMethod) =
        method.parameters.joinToString(separator = ",") { it.name }

    private
    fun sortedClassLibraryBuilderWithClassLoaderFor(classpath: FileCollection): SortedClassLibraryBuilder =
        SortedClassLibraryBuilder().apply {
            appendClassLoader(isolatedClassLoaderFor(classpath))
        }

    private
    fun isolatedClassLoaderFor(classpath: FileCollection) =
        classLoaderFactory.createIsolatedClassLoader(DefaultClassPath.of(classpath.files))

    private
    val classLoaderFactory
        get() = project.serviceOf<ClassLoaderFactory>()
}
