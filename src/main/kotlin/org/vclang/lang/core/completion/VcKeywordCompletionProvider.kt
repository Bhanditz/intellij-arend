package org.vclang.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.util.ProcessingContext

class VcKeywordCompletionProvider(
        private vararg val keywords: String
) : CompletionProvider<CompletionParameters>() {
    override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext?,
            result: CompletionResultSet
    ) {
        for (keyword in keywords) {
            var builder = LookupElementBuilder.create(keyword)
            builder = addInsertionHandler(keyword, builder)
            result.addElement(builder.withPriority(KEYWORD_PRIORITY))
        }
    }
}

fun InsertionContext.addSuffix(suffix: String) {
    document.insertString(selectionEndOffset, suffix)
    EditorModificationUtil.moveCaretRelatively(editor, suffix.length)
}

private val ALWAYS_NEEDS_SPACE = setOf(
        "\\open", "\\export", "\\hiding",
        "\\function",
        "\\infix", "\\infixl", "\\infixr",
        "\\where", "\\with",
        "\\elim",
        "\\field", "\\new",
        "\\Pi", "\\Sigma", "\\lam",
        "\\let", "\\in",
        "\\case",
        "\\with",
        "\\data", "\\class", "\\extends", "\\view",
        "\\on", "\\by",
        "\\instance",
        "\\truncated",
        "\\default",
        "\\suc", "\\max"
)

private fun addInsertionHandler(
        keyword: String,
        builder: LookupElementBuilder
): LookupElementBuilder {
    return if (keyword in ALWAYS_NEEDS_SPACE) {
        builder.withInsertHandler { context, _ -> context.addSuffix(" ") }
    } else {
        builder
    }
}
