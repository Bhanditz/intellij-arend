package org.arend.psi.ext

import com.intellij.lang.ASTNode
import org.arend.psi.ArendAtomFieldsAcc
import org.arend.term.abs.AbstractExpressionVisitor


abstract class ArendAtomFieldsAccImplMixin(node: ASTNode) : ArendExprImplMixin(node), ArendAtomFieldsAcc {
    override fun <P : Any?, R : Any?> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R {
        val fieldAccs = fieldAccList.mapNotNull { it.number?.text?.toIntOrNull() }
        return if (fieldAccs.isEmpty()) {
            if (visitor.visitErrors()) {
                visitor.reportError(org.arend.psi.ext.getErrorData(this))
            }
            atom.accept(visitor, params)
        } else {
            visitor.visitFieldAccs(this, atom, fieldAccs, if (visitor.visitErrors()) org.arend.psi.ext.getErrorData(this) else null, params)
        }
    }
}