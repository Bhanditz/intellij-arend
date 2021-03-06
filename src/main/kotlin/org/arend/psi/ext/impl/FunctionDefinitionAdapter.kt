package org.arend.psi.ext.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import org.arend.ArendIcons
import org.arend.naming.reference.ClassReferable
import org.arend.psi.*
import org.arend.psi.stubs.ArendDefFunctionStub
import org.arend.term.Precedence
import org.arend.term.abs.AbstractDefinitionVisitor
import org.arend.typing.ExpectedTypeVisitor
import org.arend.typing.ReferableExtractVisitor
import javax.swing.Icon

abstract class FunctionDefinitionAdapter : DefinitionAdapter<ArendDefFunctionStub>, ArendDefFunction {
    constructor(node: ASTNode) : super(node)

    constructor(stub: ArendDefFunctionStub, nodeType: IStubElementType<*, *>) : super(stub, nodeType)

    override fun getParameters(): List<ArendNameTele> = nameTeleList

    override fun getResultType(): ArendExpr? = returnExpr?.let { it.expr ?: it.atomFieldsAccList.firstOrNull() }

    override fun getResultTypeLevel(): ArendExpr? = returnExpr?.atomFieldsAccList?.getOrNull(1)

    override fun getTerm(): ArendExpr? = functionBody?.expr

    override fun getEliminatedExpressions(): List<ArendRefIdentifier> = functionBody?.elim?.refIdentifierList ?: emptyList()

    override fun getClauses(): List<ArendClause> = functionBody?.functionClauses?.clauseList ?: emptyList()

    override fun getPrecedence(): Precedence = calcPrecedence(prec)

    override fun withTerm() = functionBody?.fatArrow != null

    override fun isCowith() = functionBody?.cowithKw != null

    override fun isCoerce() = coerceKw != null

    override fun isLevel() = levelKw != null

    override fun isLemma() = lemmaKw != null

    override fun <R : Any?> accept(visitor: AbstractDefinitionVisitor<out R>): R = visitor.visitFunction(this)

    override fun getIcon(flags: Int): Icon = ArendIcons.FUNCTION_DEFINITION

    override fun getTypeClassReference(): ClassReferable? {
        val type = resultType ?: return null
        return if (parameters.all { !it.isExplicit }) ReferableExtractVisitor().findClassReferable(type) else null
    }

    private val allParameters
        get() = if (enclosingClass == null) parameters else listOf(ExpectedTypeVisitor.ParameterImpl(false, listOf(null), null)) + parameters

    override fun getParameterType(params: List<Boolean>) = ExpectedTypeVisitor.getParameterType(allParameters, resultType, params, textRepresentation())

    override fun getTypeOf() = ExpectedTypeVisitor.getTypeOf(allParameters, resultType)

    override fun getClassReference(): ClassReferable? {
        val type = resultType ?: return null
        return if (isCowith) ReferableExtractVisitor().findReferable(type) as? ClassReferable else ReferableExtractVisitor().findClassReferable(type)
    }

    override fun getClassReferenceData(): ClassReferenceData? {
        val type = resultType ?: return null
        val visitor = ReferableExtractVisitor(true)
        val classRef = (if (isCowith) visitor.findReferable(type) as? ClassReferable else visitor.findClassReferable(type)) ?: return null
        return ClassReferenceData(classRef, visitor.argumentsExplicitness, visitor.implementedFields)
    }

    override fun getClassFieldImpls(): List<ArendCoClause> = functionBody?.coClauses?.coClauseList ?: emptyList()

    override val psiElementType: PsiElement?
        get() = resultType
}
