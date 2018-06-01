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

package org.gradle.kotlin.dsl.codegen

import org.gradle.api.Plugin
import org.gradle.api.file.ContentFilterable
import org.gradle.api.internal.file.copy.CopySpecSource
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.PluginCollection
import org.gradle.api.tasks.AbstractCopyTask

import org.gradle.kotlin.dsl.fixtures.AbstractIntegrationTest
import org.gradle.kotlin.dsl.support.canonicalNameOf

import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.nullValue

import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Test


class ApiTypeProviderTest : AbstractIntegrationTest() {

    @Test
    fun `provides a source code generation oriented model over a classpath`() {

        val jars = listOf(withClassJar("some.jar",
            Plugin::class.java,
            PluginCollection::class.java,
            ObjectFactory::class.java))

        apiTypeProviderFor(jars).use { api ->

            assertThat(api.type<Test>(), nullValue())

            api.type<PluginCollection<*>>()!!.apply {

                assertThat(sourceName, equalTo("org.gradle.api.plugins.PluginCollection"))
                assertTrue(isPublic)
                assertThat(typeParameters.size, equalTo(1))
                typeParameters.single().apply {
                    assertThat(sourceName, equalTo("T"))
                    assertThat(bounds.size, equalTo(1))
                    assertThat(bounds.single().sourceName, equalTo("org.gradle.api.Plugin"))
                }

                functions.single { it.name == "withType" }.apply {
                    assertThat(typeParameters.size, equalTo(1))
                    typeParameters.single().apply {
                        assertThat(sourceName, equalTo("S"))
                        assertThat(bounds.size, equalTo(1))
                        assertThat(bounds.single().sourceName, equalTo("T"))
                    }
                    assertThat(parameters.size, equalTo(1))
                    parameters.single().type.apply {
                        assertThat(sourceName, equalTo("java.lang.Class"))
                        assertThat(typeArguments.size, equalTo(1))
                        typeArguments.single().apply {
                            assertThat(sourceName, equalTo("S"))
                        }
                    }
                    returnType.apply {
                        assertThat(sourceName, equalTo("org.gradle.api.plugins.PluginCollection"))
                        assertThat(typeArguments.size, equalTo(1))
                        typeArguments.single().apply {
                            assertThat(sourceName, equalTo("S"))
                        }
                    }
                }
            }
            api.type<ObjectFactory>()!!.apply {
                functions.single { it.name == "newInstance" }.apply {
                    parameters.drop(1).single().type.apply {
                        assertThat(sourceName, equalTo("kotlin.Array"))
                        assertThat(typeArguments.single().sourceName, equalTo("Any"))
                    }
                }
            }
        }
    }

    @Test
    fun `maps generic question mark to *`() {

        val jars = listOf(withClassJar("some.jar", ContentFilterable::class.java))

        apiTypeProviderFor(jars).use { api ->

            api.type<ContentFilterable>()!!.functions.single { it.name == "expand" }.apply {
                assertTrue(typeParameters.isEmpty())
                assertThat(parameters.size, equalTo(1))
                parameters.single().type.apply {
                    assertThat(sourceName, equalTo("kotlin.collections.Map"))
                    assertThat(typeArguments.size, equalTo(2))
                    assertThat(typeArguments[0].sourceName, equalTo("String"))
                    assertThat(typeArguments[1].sourceName, equalTo("*"))
                }
            }
        }
    }

    @Test
    fun `includes function overrides that change signature, excludes overrides that don't`() {
        val jars = listOf(withClassJar("some.jar", AbstractCopyTask::class.java, CopySpecSource::class.java))

        apiTypeProviderFor(jars).use { api ->
            val type = api.type<AbstractCopyTask>()!!

            assertThat(type.functions.filter { it.name == "filter" }.size, equalTo(4))

            assertThat(type.functions.filter { it.name == "getRootSpec" }.size, equalTo(0))
        }
    }

    private
    inline fun <reified T> ApiTypeProvider.type() =
        type(canonicalNameOf<T>())
}
