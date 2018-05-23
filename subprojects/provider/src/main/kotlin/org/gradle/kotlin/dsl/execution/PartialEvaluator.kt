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

package org.gradle.kotlin.dsl.execution

import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens


enum class ProgramKind {
    TopLevel,
    ScriptPlugin
}


enum class ProgramTarget {
    Project,
    Settings
}


/**
 * Reduces a [ProgramSource] into a [Program] given its [kind][ProgramKind] and [target][ProgramTarget].
 */
object PartialEvaluator {

    fun reduce(source: ProgramSource, kind: ProgramKind): Program {

        val sourceWithoutComments =
            source.map { it.erase(commentsOf(it.text)) }

        val buildscriptFragment =
            topLevelFragmentFrom(sourceWithoutComments, "buildscript")

        val pluginsFragment =
            if (kind == ProgramKind.TopLevel) topLevelFragmentFrom(sourceWithoutComments, "plugins")
            else null

        val buildscript =
            buildscriptFragment?.takeIf { it.isNotBlank() }?.let(Program::Buildscript)

        val plugins =
            pluginsFragment?.takeIf { it.isNotBlank() }?.let(Program::Plugins)

        val stage1 =
            buildscript?.let { bs ->
                plugins?.let { ps ->
                    Program.Stage1Sequence(bs, ps)
                } ?: bs
            } ?: plugins

        val remainingSource =
            sourceWithoutComments.map {
                it.erase(
                    listOfNotNull(
                        buildscriptFragment?.section?.wholeRange,
                        pluginsFragment?.section?.wholeRange))
            }

        val stage2 = remainingSource
            .takeIf { it.text.isNotBlank() }
            ?.let(Program::Script)

        stage1?.let { s1 ->
            return stage2?.let { s2 ->
                Program.Staged(s1, s2)
            } ?: s1
        }

        stage2?.let { s2 ->
            return when (kind) {
                ProgramKind.TopLevel -> s2
                ProgramKind.ScriptPlugin -> Program.PrecompiledScript(s2.source)
            }
        }

        return Program.Empty
    }

    private
    fun topLevelFragmentFrom(source: ProgramSource, identifier: String): ProgramSourceFragment? =
        extractTopLevelBlock(source.text, identifier)
            ?.let { source.fragment(it) }

    private
    fun ProgramSourceFragment.isNotBlank() =
        source.text.subSequence(section.block.start + 1, section.block.endInclusive).isNotBlank()
}


private
fun commentsOf(script: String): List<IntRange> =
    KotlinLexer().run {
        val comments = mutableListOf<IntRange>()
        start(script)
        while (tokenType != null) {
            if (tokenType in KtTokens.COMMENTS) {
                comments.add(tokenStart..(tokenEnd - 1))
            }
            advance()
        }
        comments
    }