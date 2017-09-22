package org.vclang.psi.ext.impl

import com.intellij.lang.ASTNode
import com.intellij.psi.stubs.IStubElementType
import com.jetbrains.jetpad.vclang.frontend.term.AbstractDefinitionVisitor
import org.vclang.psi.VcDefClassView
import org.vclang.psi.stubs.VcDefClassViewStub

abstract class ClassViewAdapter : DefinitionAdapter<VcDefClassViewStub>, VcDefClassView {
    constructor(node: ASTNode) : super(node)

    constructor(stub: VcDefClassViewStub, nodeType: IStubElementType<*, *>): super(stub, nodeType)

    override fun <R : Any?> accept(visitor: AbstractDefinitionVisitor<out R>?): R? = null // TODO[classes]

/* TODO[abstract]
    private var underlyingClass: Surrogate.ReferenceExpression? = null
    private var classifyingFieldName: String? = null
    private var fields: List<ClassViewFieldAdapter>? = null
    private var classifyingField: Abstract.ClassField? = null

    override val namespace: Namespace
        get() = NamespaceProvider.forDefinition(this)

    override fun getIcon(flags: Int): Icon = VcIcons.CLASS_VIEW

    fun reconstruct(
            position: Surrogate.Position?,
            name: String?,
            underlyingClass: Surrogate.ReferenceExpression?,
            classifyingFieldName: String?,
            fields: List<ClassViewFieldAdapter>?
    ): ClassViewAdapter {
        super.reconstruct(position, name, Abstract.Precedence.DEFAULT)
        this.underlyingClass = underlyingClass
        this.fields = fields
        this.classifyingFieldName = classifyingFieldName
        return this
    }

    override fun getUnderlyingClassReference(): Surrogate.ReferenceExpression =
            underlyingClass ?: throw IllegalStateException()

    override fun getClassifyingFieldName(): String =
            classifyingFieldName ?: throw IllegalStateException()

    override fun getClassifyingField(): Abstract.ClassField =
            classifyingField ?: throw IllegalStateException()

    fun setClassifyingField(classifyingField: Abstract.ClassField?) {
        this.classifyingField = classifyingField
    }

    override fun getFields(): List<ClassViewFieldAdapter> =
            fields ?: throw IllegalStateException()

    override fun <P, R> accept(visitor: AbstractDefinitionVisitor<in P, out R>, params: P): R =
            visitor.visitClassView(this, params)
    */
}
