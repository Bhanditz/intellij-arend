package org.vclang.lang.core.completion

import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement

inline fun <reified I : PsiElement> psiElement(): PsiElementPattern.Capture<I> =
        PlatformPatterns.psiElement(I::class.java)

inline fun <reified I : PsiElement> PsiElementPattern.Capture<PsiElement>.withSuperParent(
        level: Int
): PsiElementPattern.Capture<PsiElement> = this.withSuperParent(level, I::class.java)

//infix inline fun <reified I : PsiElement> ElementPattern<I>.or(
//        pattern: ElementPattern<I>
//): PsiElementPattern.Capture<PsiElement> = PlatformPatterns.psiElement().andOr(this, pattern)
