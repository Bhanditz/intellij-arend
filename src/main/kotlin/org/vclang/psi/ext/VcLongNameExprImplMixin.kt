package org.vclang.psi.ext

import com.intellij.lang.ASTNode
import com.jetbrains.jetpad.vclang.term.abs.AbstractExpressionVisitor
import org.vclang.psi.VcIntSpec
import org.vclang.psi.VcLongNameExpr
import java.math.BigInteger


private fun getIntSpec(intSpec: VcIntSpec): Pair<Boolean, BigInteger>? {
    val number = intSpec.number?.let { BigInteger(it.text) } ?: return null
    val isLowerBound = intSpec.intBraceG != null || intSpec.intBraceGe != null
    return if (!isLowerBound && intSpec.intBraceL == null && intSpec.intBraceLe == null) null else Pair(isLowerBound, number)
}

abstract class VcLongNameExprImplMixin(node: ASTNode) : VcExprImplMixin(node), VcLongNameExpr {
    override fun <P : Any?, R : Any?> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R {
        val name = longName

        val intSpecList = intSpecList
        if (!intSpecList.isEmpty()) {
            val intSpec1 = intSpecList.getOrNull(0)?.let { getIntSpec(it) }
            val intSpec2 = intSpecList.getOrNull(1)?.let { getIntSpec(it) }
            return visitor.visitReference(name, name.referent, intSpec1 != null && intSpec1.first, intSpec1?.second, intSpec2 != null && intSpec2.first, intSpec2?.second, if (visitor.visitErrors()) org.vclang.psi.ext.getErrorData(this) else null, params)
        }

        val levels = generateSequence(levelsExpr) { it.levelsExpr }.lastOrNull()
        if (levels != null) {
            levels.propKw?.let { return visitor.visitReference(name, name.referent, 0, -1, if (visitor.visitErrors()) org.vclang.psi.ext.getErrorData(this) else null, params) }
            val levelExprList = levels.atomLevelExprList
            if (levelExprList.size == 2) {
                return visitor.visitReference(name, name.referent, levelExprList[0], levelExprList[1], if (visitor.visitErrors()) org.vclang.psi.ext.getErrorData(this) else null, params)
            }
        }

        val levelExprList = atomOnlyLevelExprList
        return visitor.visitReference(name, name.referent, levelExprList.getOrNull(0), levelExprList.getOrNull(1), if (visitor.visitErrors()) org.vclang.psi.ext.getErrorData(this) else null, params)
    }
}