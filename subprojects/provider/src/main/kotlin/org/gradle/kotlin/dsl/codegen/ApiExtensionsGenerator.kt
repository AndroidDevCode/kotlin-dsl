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

@file:JvmName("ApiExtensionsGenerator")

package org.gradle.kotlin.dsl.codegen

import org.gradle.api.file.RelativePath
import org.gradle.api.specs.Spec
import org.gradle.api.specs.Specs

import org.gradle.api.internal.file.pattern.PatternMatcherFactory
import org.gradle.util.GUtil

import java.io.File


/**
 * Generate source file with Kotlin extensions enhancing the given api for the Gradle Kotlin DSL.
 *
 * @param outputFile the file where the generated source will be written
 * @param classPath the api classpath elements
 * @param additionalClassPath the api classpath additional elements
 * @param includes the api include patterns
 * @param excludes the api exclude patterns
 * @param parameterNamesIndices the api function parameter names indices
 */
fun generateKotlinDslApiExtensionsSourceTo(
    outputFile: File,
    classPath: List<File>,
    additionalClassPath: List<File>,
    includes: List<String>,
    excludes: List<String>,
    parameterNamesIndices: List<File>
): Unit =

    outputFile.bufferedWriter().use {
        it.apply {
            write(fileHeader)
            write("\n")
            apiTypeProviderFor(classPath, additionalClassPath, parameterNamesSupplierFor(parameterNamesIndices)).use { api ->
                kotlinDslApiExtensionsDeclarationsFor(api, apiSpecFor(includes, excludes)).forEach {
                    write("\n$it")
                }
            }
        }
    }


private
fun parameterNamesSupplierFor(parameterNamesIndices: List<File>): ParameterNamesSupplier =
    parameterNamesIndices
        .map { GUtil.loadProperties(it) }
        .reduce { acc, properties -> acc.apply { putAll(properties) } }
        .let { index ->
            { key: String ->
                index.getProperty(key, null)?.split(",")
            }
        }


private
fun apiSpecFor(includes: List<String>, excludes: List<String>) =
    Specs.intersect(patternSpecFor(includes), Specs.negate(patternSpecFor(excludes)))


private
fun patternSpecFor(patterns: List<String>) =
    Specs.union(patterns.map {
        PatternMatcherFactory.getPatternMatcher(true, true, it)
    })


internal
fun kotlinDslApiExtensionsDeclarationsFor(api: ApiTypeProvider, apiSpec: Spec<RelativePath>): Sequence<String> =
    api.allTypes()
        .filter { type ->
            apiSpec.isSatisfiedBy(RelativePath.parse(true, type.sourceName.replace(".", File.separator)))
                && type.isPublic
        }
        .flatMap { type -> kotlinExtensionFunctionsFor(type) }
        .distinctBy(::signatureKey)
        .map { it.toKotlinString() }


private
fun signatureKey(extension: KotlinExtensionFunction): List<Any> = extension.run {
    (listOf(targetType.sourceName, name)
        + parameters.flatMap { apiTypeKey(it.type) })
}


private
fun apiTypeKey(usage: ApiTypeUsage): List<Any> = usage.run {
    (listOf(sourceName, isNullable, isRaw)
        + typeArguments.flatMap(::apiTypeKey)
        + bounds.flatMap(::apiTypeKey))
}


private
fun kotlinExtensionFunctionsFor(type: ApiType): Sequence<KotlinExtensionFunction> =
    type.functions.candidatesForExtension.asSequence()
        .sortedWithTypeOfTakingFunctionsFirst()
        .flatMap { function ->

            val candidateFor = object {
                val groovyNamedArgumentsToVarargs = function.parameters.firstOrNull()?.type?.isGroovyNamedArgumentMap == true
                val javaClassToKotlinClass = function.parameters.any {
                    it.type.isJavaClass || (it.type.isKotlinArray && it.type.typeArguments.single().isJavaClass) || (it.type.isKotlinCollection && it.type.typeArguments.single().isJavaClass)
                }

                val extension
                    get() = groovyNamedArgumentsToVarargs || javaClassToKotlinClass
            }

            if (!candidateFor.extension) {
                return@flatMap emptySequence<KotlinExtensionFunction>()
            }

            val extensionTypeParameters = function.typeParameters + type.typeParameters

            sequenceOf(KotlinExtensionFunction(
                description = "Kotlin extension function ${if (candidateFor.javaClassToKotlinClass) "taking [kotlin.reflect.KClass] " else ""}for [${type.sourceName}.${function.name}]",
                isIncubating = function.isIncubating,
                isDeprecated = function.isDeprecated,
                typeParameters = extensionTypeParameters,
                targetType = type,
                name = function.name,
                parameters = function.newMappedParameters().groovyNamedArgumentsToVarargs().javaClassToKotlinClass(),
                returnType = function.returnType))
        }


private
fun Sequence<ApiFunction>.sortedWithTypeOfTakingFunctionsFirst() =
    sortedBy { f ->
        if (f.parameters.any { it.type.isGradleTypeOf }) 0
        else 1
    }


private
fun ApiFunction.newMappedParameters() =
    parameters.map { MappedApiFunctionParameter(it) }


private
data class MappedApiFunctionParameter(
    val original: ApiFunctionParameter,
    val index: Int = original.index,
    val type: ApiTypeUsage = original.type,
    val invocation: String = "`${original.name ?: "p$index"}`"
) {
    val name: String
        get() = original.name ?: "p$index"
}


private
fun List<MappedApiFunctionParameter>.groovyNamedArgumentsToVarargs() =
    firstOrNull()?.takeIf { it.type.isGroovyNamedArgumentMap }?.let { first ->
        drop(1) + first.copy(
            type = ApiTypeUsage(
                sourceName = SourceNames.kotlinArray,
                typeArguments = listOf(
                    ApiTypeUsage(
                        "Pair",
                        typeArguments = listOf(
                            ApiTypeUsage("String"), starProjectionTypeUsage)))),
            invocation = "mapOf(*${first.invocation})")
    } ?: this


private
fun List<MappedApiFunctionParameter>.javaClassToKotlinClass() =
    map { p ->
        if (p.type.isJavaClass) p.copy(type = p.type.toKotlinClass(), invocation = "${p.invocation}.java")
        else if (p.type.isKotlinArray && p.type.typeArguments.single().isJavaClass) p.copy(type = p.type.toArrayOfKotlinClasses(), invocation = "*${p.invocation}.map { it.java }.toTypedArray()")
        else if (p.type.isKotlinCollection && p.type.typeArguments.single().isJavaClass) p.copy(type = p.type.toCollectionOfKotlinClasses(), invocation = "${p.invocation}.map { it.java }")
        else p
    }


private
data class KotlinExtensionFunction(
    val description: String,
    val isIncubating: Boolean,
    val isDeprecated: Boolean,
    val typeParameters: List<ApiTypeUsage>,
    val targetType: ApiType,
    val name: String,
    val parameters: List<MappedApiFunctionParameter>,
    val returnType: ApiTypeUsage
) {


    fun toKotlinString(): String = StringBuilder().apply {

        appendln("""
            /**
             * $description.
             */
        """.trimIndent())
        if (isDeprecated) appendln("""@Deprecated("Deprecated Gradle API")""")
        if (isIncubating) appendln("@org.gradle.api.Incubating")
        append("fun ")
        if (typeParameters.isNotEmpty()) append("${typeParameters.joinInAngleBrackets { it.toTypeParameterString() }} ")
        append(targetType.sourceName)
        if (targetType.typeParameters.isNotEmpty()) append(targetType.typeParameters.toTypeArgumentsString(targetType))
        append(".")
        append("`$name`")
        append("(")
        append(parameters.toDeclarationString())
        append("): ")
        append(returnType.toTypeArgumentString())
        appendln(" =")
        appendln("`$name`(${parameters.toInvocationString()})".prependIndent())
        appendln()
    }.toString()


    private
    fun List<MappedApiFunctionParameter>.toDeclarationString(): String =
        takeIf { it.isNotEmpty() }?.let { list ->
            list.mapIndexed { index, p ->
                if (index == list.size - 1 && p.type.isKotlinArray) "vararg `${p.name}`: ${p.type.typeArguments.single().toTypeArgumentString()}"
                else if (p.type.isGradleAction) "`${p.name}`: ${p.type.typeArguments.single().toTypeArgumentString()}.() -> Unit"
                else "`${p.name}`: ${p.type.toTypeArgumentString()}"
            }.joinToString(separator = ", ")
        } ?: ""


    private
    fun List<MappedApiFunctionParameter>.toInvocationString(): String =
        takeIf { it.isNotEmpty() }
            ?.sortedBy { it.original.index }
            ?.joinToString(separator = ", ") { it.invocation }
            ?: ""
}


private
fun ApiTypeUsage.toKotlinClass() =
    ApiTypeUsage(SourceNames.kotlinClass, isNullable, typeArguments = typeArguments)


private
fun ApiTypeUsage.toArrayOfKotlinClasses() =
    ApiTypeUsage(SourceNames.kotlinArray, isNullable, typeArguments = listOf(ApiTypeUsage(SourceNames.kotlinClass, typeArguments = typeArguments.single().typeArguments)))


private
fun ApiTypeUsage.toCollectionOfKotlinClasses() =
    ApiTypeUsage(SourceNames.kotlinCollection, isNullable, typeArguments = listOf(ApiTypeUsage(SourceNames.kotlinClass, typeArguments = typeArguments.single().typeArguments)))


private
fun Boolean.toKotlinNullabilityString(): String =
    if (this) "?" else ""


private
fun ApiTypeUsage.toTypeParameterString(): String =
    "$sourceName${
    bounds.takeIf { it.isNotEmpty() }?.let { " : ${it.single().toTypeParameterString()}" } ?: ""
    }${typeArguments.toTypeParametersString(type)}${isNullable.toKotlinNullabilityString()}"


private
fun List<ApiTypeUsage>.toTypeParametersString(type: ApiType? = null): String =
    rawTypesToStarProjections(type).joinInAngleBrackets { it.toTypeParameterString() }


private
fun ApiTypeUsage.toTypeArgumentString(): String =
    "$sourceName${typeArguments.toTypeArgumentsString(type)}${isNullable.toKotlinNullabilityString()}"


private
fun List<ApiTypeUsage>.toTypeArgumentsString(type: ApiType? = null): String =
    rawTypesToStarProjections(type).joinInAngleBrackets { it.toTypeArgumentString() }


private
fun List<ApiTypeUsage>.rawTypesToStarProjections(type: ApiType? = null): List<ApiTypeUsage> =
    when {
        isNotEmpty() -> this
        type?.typeParameters?.isNotEmpty() == true -> List(type.typeParameters.size) { starProjectionTypeUsage }
        else -> emptyList()
    }


private
fun <T> List<T>?.joinInAngleBrackets(transform: (T) -> CharSequence = { it.toString() }) =
    this?.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = ", ", prefix = "<", postfix = ">", transform = transform)
        ?: ""


private
val ApiTypeUsage.isGroovyNamedArgumentMap
    get() = isMap && (
        typeArguments.all { it.isAny }
            || typeArguments.all { it.isStarProjectionTypeUsage }
            || (typeArguments[0].isString && (typeArguments[1].isStarProjectionTypeUsage || typeArguments[1].isAny))
        )


private
object SourceNames {
    const val javaClass = "java.lang.Class"
    const val groovyClosure = "groovy.lang.Closure"
    const val gradleAction = "org.gradle.api.Action"
    const val gradleTypeOf = "org.gradle.api.reflect.TypeOf"
    const val kotlinClass = "kotlin.reflect.KClass"
    const val kotlinArray = "kotlin.Array"
    const val kotlinCollection = "kotlin.collections.Collection"
}


private
val ApiTypeUsage.isAny
    get() = sourceName == "Any"


private
val ApiTypeUsage.isString
    get() = sourceName == "String"


private
val ApiTypeUsage.isMap
    get() = sourceName == "kotlin.collections.Map"


private
val ApiTypeUsage.isJavaClass
    get() = sourceName == SourceNames.javaClass


private
val ApiTypeUsage.isGroovyClosure
    get() = sourceName == SourceNames.groovyClosure


private
val ApiTypeUsage.isGradleAction
    get() = sourceName == SourceNames.gradleAction


private
val ApiTypeUsage.isGradleTypeOf
    get() = sourceName == SourceNames.gradleTypeOf


private
val ApiTypeUsage.isKotlinArray
    get() = sourceName == SourceNames.kotlinArray


private
val ApiTypeUsage.isKotlinCollection
    get() = sourceName == SourceNames.kotlinCollection


private
val List<ApiFunction>.candidatesForExtension: List<ApiFunction>
    get() = filter {
        it.name !in functionNameBlackList
            && it.isPublic
            && !it.isStatic
            && it.parameters.none { it.type.isGroovyClosure }
    }


private
val functionNameBlackList = listOf("<init>", "apply")
