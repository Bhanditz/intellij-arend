package org.arend.hierarchy

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Comparing
import com.intellij.psi.PsiElement
import org.arend.psi.ext.PsiLocatedReferable

class ArendHierarchyNodeDescriptor(project: Project, parent: HierarchyNodeDescriptor?,
                                   element: PsiElement, isBase: Boolean) : HierarchyNodeDescriptor(project, parent, element, isBase) {
    //fun getReferenceElement() : PsiGlobalReferable? {
    //    return (psiElement as ArendReferenceElement).reference?.resolve() as PsiGlobalReferable
   // }
    override fun update(): Boolean {
        val oldText = myHighlightedText

        if (psiElement != null && myHighlightedText.ending.appearance.text == "") {
            myHighlightedText.ending.addText((psiElement as PsiLocatedReferable).name)
        }

        return !Comparing.equal(myHighlightedText, oldText) || super.update()
    }
}