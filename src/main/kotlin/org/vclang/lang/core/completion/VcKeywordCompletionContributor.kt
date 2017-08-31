package org.vclang.lang.core.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.project.DumbAware
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.patterns.PsiElementPattern
import com.intellij.patterns.StandardPatterns.or
import com.intellij.psi.PsiElement
import org.vclang.lang.core.VcPsiPattern
import org.vclang.lang.core.psi.*
import org.vclang.lang.core.psi.VcElementTypes.*
import org.vclang.lang.core.withPrevItem

//statementContext: OPEN_KW, EXPORT_KW, FUNCTION_KW, TRUNCATED_KW, DATA_KW, CLASS_KW, VIEW_KW, DEFAULT_KW, INSTANCE_KW
//exprContext: CASE_KW, PI_KW, SIGMA_KW, LAM_KW, LET_KW
//
//statement -> statCmd: HIDING_KW
//elimContext: ELIM_KW
//letContext: IN_KW
//defClassViewContext: ON_KW, BY_KW
//caseContext: WITH_KW
//defClassContext: EXTENDS_KW
//
//atomLevelContext: LP_KW, LH_KW
//levelContext: LP_KW, LH_KW, SUC_KW, MAX_KW
//precedenceContext: NON_ASSOC_KW, LEFT_ASSOC_KW, RIGHT_ASSOC_KW
//literalContext: PROP_KW
//newContext: NEW_KW
//elimContext: WITH_KW
//whereContext: WHERE_KW


class VcKeywordCompletionContributor : CompletionContributor(), DumbAware {

    init {
        extend(CompletionType.BASIC, statementPattern(), VcKeywordCompletionProvider( // TODO: Fix?
                "\\function", "\\class", "\\data", "\\instance",
                "\\open", "\\truncated", "\\default", "\\export", "\\view"
        ))

        extend(CompletionType.BASIC, expressionPattern(), VcKeywordCompletionProvider( // TODO: Test
                "\\case", "\\Pi", "\\Sigma", "\\lam", "\\let"
        ))

        extend(CompletionType.BASIC, hidingPattern(), VcKeywordCompletionProvider(
                "\\hiding"
        ))

        extend(CompletionType.BASIC, elimPattern(), VcKeywordCompletionProvider(
                "\\elim"
        ))

        extend(CompletionType.BASIC, letInPattern(), VcKeywordCompletionProvider(
                "\\in"
        ))

//        extend(CompletionType.BASIC, classViewPattern(), VcKeywordCompletionProvider(
//                "\\on", "\\by"
//        ))

        extend(CompletionType.BASIC, withPattern(), VcKeywordCompletionProvider(
                "\\with"
        ))

        extend(CompletionType.BASIC, extendsPattern(), VcKeywordCompletionProvider(
                "\\extends"
        ))

        extend(CompletionType.BASIC, atomLevelPattern(), VcKeywordCompletionProvider(
                "\\lp", "\\lh"
        ))

//        extend(CompletionType.BASIC, levelPattern(), VcKeywordCompletionProvider(
//                "\\lp", "\\lh", "\\suc", "\\max"
//        ))

//        extend(CompletionType.BASIC, assocPattern(), VcKeywordCompletionProvider(
//                "\\infix", "\\infixl", "\\infixr"
//        ))

        extend(CompletionType.BASIC, literalPattern(), VcKeywordCompletionProvider(
                "\\Prop"
        ))

        extend(CompletionType.BASIC, newPattern(), VcKeywordCompletionProvider(
                "\\new"
        ))

//        extend(CompletionType.BASIC, wherePattern(), VcKeywordCompletionProvider(
//                "\\where"
//        ))
    }

    private fun statementPattern(): PsiElementPattern.Capture<PsiElement> {
        val statementBeginningPattern = psiElement(PREFIX).and(VcPsiPattern.onStatementBeginning)
        val toplevelPattern = psiElement().inside(psiElement<VcFile>())
        val insideWherePattern = psiElement().inside(psiElement<VcWhere>())
        return statementBeginningPattern.andOr(toplevelPattern, insideWherePattern)
    }

    private fun expressionPattern(): PsiElementPattern.Capture<PsiElement> =
            psiElement().inside(psiElement<VcExpr>()).and(VcPsiPattern.onExpressionBeginning)

    private fun hidingPattern(): PsiElementPattern.Capture<PsiElement> {
        return psiElement(PREFIX).withPrevItem(or(
                psiElement(MODULE_PATH).inside(psiElement<VcNsCmdRoot>()),
                psiElement().inside(psiElement<VcFieldAcc>())
        ))
    }

    private fun elimPattern(): PsiElementPattern.Capture<PsiElement> {
        return psiElement(PREFIX)
                .inside(psiElement<VcFunctionBody>())
                .withPrevItem(psiElement(FAT_ARROW))
    }

    private fun letInPattern(): PsiElementPattern.Capture<PsiElement> {
        return psiElement(PREFIX)
                .withPrevItem(psiElement().inside(psiElement<VcLetClause>()))
    }

    private fun withPattern(): PsiElementPattern.Capture<PsiElement> {
        return psiElement(PREFIX)
                .withPrevItem(psiElement().inside(psiElement<VcLetClause>()))
    }

    private fun extendsPattern(): PsiElementPattern.Capture<PsiElement> =
            psiElement().inside(psiElement<VcClassTeles>())

    private fun atomLevelPattern(): PsiElementPattern.Capture<PsiElement> {
        return psiElement().withPrevItem(or(
                psiElement(SET),
                psiElement(UNIVERSE),
                psiElement(TRUNCATED_UNIVERSE)
        ), true)
    }

    private fun literalPattern(): PsiElementPattern.Capture<PsiElement> =
            psiElement().inside(psiElement<VcLiteral>())

    private fun newPattern(): PsiElementPattern.Capture<PsiElement> =
            psiElement().inside(psiElement<VcBinOpExpr>())

    private fun wherePattern(): PsiElementPattern.Capture<PsiElement> =
            psiElement().withSuperParent(4, psiElement<VcClassTeles>())
}
