package org.vclang.lang.core.parser

import com.intellij.psi.PsiElement
import com.jetbrains.jetpad.vclang.error.ErrorReporter
import com.jetbrains.jetpad.vclang.frontend.Concrete
import com.jetbrains.jetpad.vclang.frontend.parser.ParseException
import com.jetbrains.jetpad.vclang.frontend.parser.ParserError
import com.jetbrains.jetpad.vclang.module.source.SourceId
import com.jetbrains.jetpad.vclang.term.Abstract
import com.jetbrains.jetpad.vclang.term.legacy.LegacyAbstract
import org.vclang.lang.core.Surrogate
import org.vclang.lang.core.psi.*
import org.vclang.lang.core.psi.ext.adapters.*

class AbstractTreeBuildVisitor(
        private val module: SourceId,
        private val errorReporter: ErrorReporter
) {

    fun visitModule(context: VcFile): VcFile {
        val statementsContext = context.childOfType<VcStatements>()
        val globalStatements = statementsContext?.let { visitStatements(it) }
        globalStatements ?: return context
        globalStatements.let { context.globalStatements = it }
        return context
    }

    fun visitStatements(context: VcStatements): List<Surrogate.Statement> =
            visitStatementList(context.statementList)

    private fun visitStatementList(context: List<VcStatement>): MutableList<Surrogate.Statement> =
            context.map { visitStatement(it) }.toMutableList()

    fun visitStatement(context: VcStatement): Surrogate.Statement {
        context.statCmd?.let { return visitStatCmd(it) }
        context.statDef?.let { return visitStatDef(it) }
        throw IllegalStateException()
    }

    fun visitStatCmd(context: VcStatCmd): Surrogate.NamespaceCommandStatement {
        val kind = visitNsCmd(context.nsCmd)
        val modulePath = context.nsCmdRoot?.moduleName?.let { visitModulePath(it) }
        val path = mutableListOf<String>()
        context.nsCmdRoot?.identifier?.let { path.add(it.text) }
        for (fieldAcc in context.fieldAccList) {
            fieldAcc.identifier?.let { path.add(it.text) }
                    ?: reportError(elementPosition(fieldAcc), "Expected a name")
        }
        val identifiers = context.identifierList
        val names = if (identifiers.isNotEmpty()) identifiers.map { it.text } else null
        return Surrogate.NamespaceCommandStatement(
                elementPosition(context),
                kind,
                modulePath,
                path,
                context.isHiding,
                names
        )
    }

    fun visitNsCmd(context: VcNsCmd): LegacyAbstract.NamespaceCommandStatement.Kind = when {
        context.isExportCmd -> LegacyAbstract.NamespaceCommandStatement.Kind.EXPORT
        context.isOpenCmd -> LegacyAbstract.NamespaceCommandStatement.Kind.OPEN
        else -> throw IllegalStateException()
    }

    fun visitStatDef(context: VcStatDef): Surrogate.DefineStatement {
        val definition = visitDefinition(context.definition)
        return Surrogate.DefineStatement(elementPosition(definition), definition)
    }

    // Definitions

    fun visitDefinition(context: VcDefinition): DefinitionAdapter = when (context) {
        is VcDefClass -> visitDefClass(context)
        is VcDefClassView -> visitDefClassView(context)
        is VcDefData -> visitDefData(context)
        is VcDefFunction -> visitDefFunction(context)
        is VcDefInstance -> visitDefInstance(context)
        else -> {
            val childDef = context.childOfType<VcDefinition>()
            childDef?.let { return visitDefinition(it) }
            throw IllegalStateException()
        }
    }

    fun visitDefClass(context: VcDefClass): ClassDefinitionAdapter {
        val name = context.identifier?.text
        val polyParams = visitTeles(context.teleList)
        val superClasses = context.atomFieldsAccList.map {
            Surrogate.SuperClass(elementPosition(it), visitAtomFieldsAcc(it))
        }
        val fields = mutableListOf<ClassFieldAdapter>()
        val implementations = mutableListOf<ClassImplementAdapter>()
        val globalStatements = visitWhere(context.where)
        val instanceDefinitions = visitInstanceStatements(
                context.classStatList,
                fields,
                implementations
        )

        if (context !is ClassDefinitionAdapter) throw IllegalStateException()
        val classDefinition = context.reconstruct(
                elementPosition(context),
                name,
                polyParams,
                superClasses,
                fields,
                implementations,
                globalStatements,
                instanceDefinitions
        )

        fields.forEach { it.setParent(classDefinition) }
        implementations.forEach { it.setParent(classDefinition) }
        instanceDefinitions.forEach {
            it.setParent(classDefinition)
            it.setNotStatic()
        }
        globalStatements
                .filterIsInstance<Surrogate.DefineStatement>()
                .forEach { it.definition.setParent(classDefinition) }

        return classDefinition
    }

    fun visitClassStat(context: VcClassStat): Abstract.SourceNode {
        context.classField?.let { return visitClassField(it) }
        context.classImplement?.let { return visitClassImplement(it) }
        context.statement?.let { return visitStatement(it) }
        throw IllegalStateException()
    }

    fun visitClassField(context: VcClassField): ClassFieldAdapter {
        if (context is ClassFieldAdapter) {
            return context.reconstruct(
                    elementPosition(context),
                    context.identifier.text,
                    visitPrecedence(context.prec),
                    visitExpr(context.expr)
            )
        }
        throw IllegalStateException()
    }

    fun visitClassImplement(context: VcClassImplement): ClassImplementAdapter {
        if (context !is ClassImplementAdapter) throw IllegalStateException()
        return context.reconstruct(
                elementPosition(context),
                context.identifier.text,
                visitExpr(context.expr)
        )
    }

    private fun visitInstanceStatements(
            context: List<VcClassStat>,
            fields: MutableList<ClassFieldAdapter>?,
            implementations: MutableList<ClassImplementAdapter>?
    ): List<DefinitionAdapter> {
        val definitions = mutableListOf<DefinitionAdapter>()
        for (classStatContext in context) {
            try {
                val sourceNode = visitClassStat(classStatContext)
                val definition = if (sourceNode is Surrogate.DefineStatement) {
                    sourceNode.definition
                } else if (sourceNode is DefinitionAdapter) {
                    sourceNode
                } else {
                    reportError(elementPosition(sourceNode as PsiElement), MISPLACES_DEFINITION)
                    continue
                }

                when (definition) {
                    is ClassFieldAdapter -> fields?.add(definition)
                            ?: reportError(elementPosition(definition), MISPLACES_DEFINITION)
                    is ClassImplementAdapter -> implementations?.add(definition)
                            ?: reportError(elementPosition(definition), MISPLACES_DEFINITION)
                    is FunctionDefinitionAdapter,
                    is DataDefinitionAdapter,
                    is ClassDefinitionAdapter -> definitions.add(definition)
                    else -> reportError(elementPosition(definition), MISPLACES_DEFINITION)
                }
            } catch (ignored: ParseException) {

            }

        }
        return definitions
    }

    fun visitDefClassView(context: VcDefClassView): ClassViewAdapter {
        if (context !is ClassViewAdapter) throw IllegalStateException()
        val name = context.identifierList[0]?.text
        val underlyingClass = visitExpr(context.expr)
        if (underlyingClass !is Surrogate.ReferenceExpression) {
            reportError(underlyingClass.position, "Expected a class")
            throw ParseException()
        }
        val classifyingFieldName = context.identifierList[1]?.text
        val fields = mutableListOf<ClassViewFieldAdapter>()
        val classView = context.reconstruct(
                elementPosition(context),
                name,
                underlyingClass,
                classifyingFieldName,
                fields
        )
        context.classViewFieldList.mapTo(fields) { visitClassViewField(it, classView) }
        return classView
    }

    fun visitDefData(context: VcDefData): DataDefinitionAdapter {
        val universe: Surrogate.UniverseExpression? = context.expr?.let {
            val expr = visitExpr(it)
            if (expr is Surrogate.UniverseExpression) {
                expr
            } else {
                val message = "Specified type of the data definition is not a universe"
                reportError(elementPosition(it), message)
                null
            }
        }

        val eliminatedReferences = context.dataBody?.dataClauses?.elim?.let { visitElim(it) }
        if (context !is DataDefinitionAdapter) throw IllegalStateException()
        val dataDefinition = context.reconstruct(
                elementPosition(context),
                context.identifier?.text,
                visitPrecedence(context.prec),
                visitTeles(context.teleList),
                eliminatedReferences,
                universe,
                mutableListOf()
        )

        visitDataBody(context.dataBody, dataDefinition)
        return dataDefinition
    }

    fun visitDefFunction(context: VcDefFunction): FunctionDefinitionAdapter {
        val resultType = context.expr?.let { visitExpr(context.expr) }
        val body: Surrogate.FunctionBody = context.functionBody.let {
            val elimContext = it?.withElim
            if (elimContext != null) {
                return@let Surrogate.ElimFunctionBody(
                        elementPosition(elimContext),
                        visitElim(elimContext.elim),
                        visitClauses(elimContext.clauses)
                )
            }
            val withoutElimContext = it?.withoutElim
            if (withoutElimContext != null) {
                return@let Surrogate.TermFunctionBody(
                        elementPosition(withoutElimContext),
                        visitExpr(withoutElimContext.expr)
                )
            }
            throw IllegalStateException()
        }
        val statements = visitWhere(context.where)

        if (context !is FunctionDefinitionAdapter) throw IllegalStateException()
        val functionDefinition = context.reconstruct(
                elementPosition(context),
                context.identifier?.text,
                visitPrecedence(context.prec),
                visitFunctionArguments(context.teleList),
                resultType,
                body,
                statements
        )

        val statementsIterator = statements.iterator()
        for (statement in statementsIterator) {
            if (statement is Surrogate.DefineStatement) {
                val definition = statement.definition
                if (definition is ClassFieldAdapter || definition is ClassImplementAdapter) {
                    reportError(elementPosition(definition), MISPLACES_DEFINITION)
                    statementsIterator.remove()
                } else {
                    definition.setParent(functionDefinition)
                }
            }
        }

        return functionDefinition
    }

    fun visitDefInstance(context: VcDefInstance): ClassViewInstanceAdapter {
        val term = visitExpr(context.expr)
        if (term is Surrogate.NewExpression) {
            val type = term.expression
            if (type is Surrogate.ClassExtExpression) {
                if (type.baseClassExpression is Surrogate.ReferenceExpression) {
                    val name = context.identifier?.text
                    if (context !is ClassViewInstanceAdapter) throw IllegalStateException()
                    return context.reconstruct(
                            elementPosition(context),
                            context.isDefault,
                            name,
                            Abstract.Precedence.DEFAULT,
                            visitFunctionArguments(context.teleList),
                            type.baseClassExpression as Surrogate.ReferenceExpression,
                            type.statements
                    )
                }
            }
        }

        reportError(elementPosition(context.expr), "Expected a class view extension")
        throw ParseException()
    }

    fun visitDataBody(context: VcDataBody?, def: DataDefinitionAdapter) {
        context?.dataClauses?.let { return visitDataClauses(it, def) }
        context?.dataConstructors?.let { return visitDataConstructors(it, def) }
        throw IllegalStateException()
    }

    fun visitDataClauses(context: VcDataClauses, def: DataDefinitionAdapter) {
        for (clauseCtx in context.constructorClauseList) {
            try {
                def.constructorClauses.add(
                        Surrogate.ConstructorClause(
                                elementPosition(clauseCtx),
                                clauseCtx.patternList.map { visitPattern(it) },
                                visitConstructors(clauseCtx.constructorList, def)
                        )
                )
            } catch (ignored: ParseException) {
            }
        }
    }

    fun visitDataConstructors(context: VcDataConstructors, def: DataDefinitionAdapter) {
        def.constructorClauses.add(
                Surrogate.ConstructorClause(
                        elementPosition(context),
                        null,
                        visitConstructors(context.constructorList, def)
                )
        )
    }

    fun visitElim(context: VcElim?): List<Surrogate.ReferenceExpression>? {
        return if (context != null && context.atomFieldsAccList.isNotEmpty()) {
            checkElimExpressions(context.atomFieldsAccList.map { visitAtomFieldsAcc(it) })
        } else {
            mutableListOf()
        }
    }

    private fun checkElimExpressions(
            expressions: List<Surrogate.Expression>
    ): List<Surrogate.ReferenceExpression>? {
        val predicate: (Surrogate.Expression) -> Boolean =
                { it !is Surrogate.ReferenceExpression || it.expression != null }
        expressions.firstOrNull(predicate)?.let {
            reportError(it.position, "\\elim can be applied only to a local variable")
            return null
        }
        return expressions.filterIsInstance<Surrogate.ReferenceExpression>()
    }

    fun visitClassViewField(
            context: VcClassViewField,
            ownView: ClassViewAdapter
    ): ClassViewFieldAdapter {
        val underlyingField = context.identifierList[0].text
        val name = if (context.identifierList.size > 1) {
            context.identifierList[1].text
        } else {
            underlyingField
        }
        if (context !is ClassViewFieldAdapter) throw IllegalStateException()
        return context.reconstruct(
                elementPosition(context.identifierList[0]),
                name,
                visitPrecedence(context.prec),
                underlyingField,
                ownView
        )
    }

    fun visitWhere(context: VcWhere?): MutableList<Surrogate.Statement> {
        return if (context != null && context.statementList.isNotEmpty()) {
            visitStatementList(context.statementList)
        } else {
            mutableListOf()
        }
    }

    // Patterns

    fun visitPattern(context: VcPattern): Surrogate.Pattern {
        context.atomPattern?.let { return visitAtomPattern(it) }
        context.patternConstructor?.let { return visitPatternConstructor(it) }
        throw IllegalStateException()
    }

    fun visitPatternConstructor(context: VcPatternConstructor): Surrogate.Pattern {
        return if (context.atomPatternOrPrefixList.isEmpty()) {
            Surrogate.NamePattern(elementPosition(context), visitPrefix(context.prefixName))
        } else {
            Surrogate.ConstructorPattern(
                    elementPosition(context),
                    visitPrefix(context.prefixName),
                    context.atomPatternOrPrefixList.map { visitAtomPatternOrID(it) }
            )
        }
    }

    fun visitAtomPatternOrID(context: VcAtomPatternOrPrefix): Surrogate.Pattern {
        context.atomPattern?.let { return visitAtomPattern(it) }
        val name = context.prefixName?.let { visitPrefix(it) }
        return Surrogate.NamePattern(elementPosition(context), name)
    }

    fun visitAtomPattern(context: VcAtomPattern): Surrogate.Pattern = when {
        context.isExplicit || context.isImplicit -> {
            val pattern = visitPattern(context.pattern!!)
            pattern.isExplicit = context.isExplicit
            pattern
        }
        context.isAny -> Surrogate.NamePattern(elementPosition(context), "_")
        context.isEmpty -> Surrogate.EmptyPattern(elementPosition(context))
        else -> throw IllegalStateException()
    }

    private fun visitConstructors(
            context: List<VcConstructor>,
            definition: DataDefinitionAdapter
    ): List<ConstructorAdapter> = context.map {
        if (it !is ConstructorAdapter) throw IllegalStateException()
        val hasConditions = it.elim != null || it.clauseList.isNotEmpty()
        it.reconstruct(
                elementPosition(it),
                it.identifier.text,
                visitPrecedence(it.prec),
                definition,
                visitTeles(it.teleList),
                visitElim(it.elim),
                if (hasConditions) it.clauseList.map { visitClause(it) } else emptyList()
        )
    }

    fun visitPrecedence(context: VcPrec?): Abstract.Precedence {
        val associativity = context?.associativity ?: return Abstract.Precedence.DEFAULT
        val priority = Integer.parseInt(context.number?.text).let {
            if (it < 1 || it > 9) {
                reportError(elementPosition(context.number), "Precedence out of range: $it")
            }
            it.coerceIn(1, 9)
        }
        return Abstract.Precedence(visitAssociativity(associativity), priority.toByte())
    }

    fun visitAssociativity(context: VcAssociativity): Abstract.Precedence.Associativity = when {
        context.isLeftAssoc -> Abstract.Precedence.Associativity.LEFT_ASSOC
        context.isRightAssoc -> Abstract.Precedence.Associativity.RIGHT_ASSOC
        context.isNonAssoc -> Abstract.Precedence.Associativity.NON_ASSOC
        else -> throw IllegalStateException()
    }

    fun visitExpr0(context: VcExpr0): Surrogate.Expression {
        return parseBinOpSequence(
                context.binOpLeftList,
                visitBinOpArg(context.binOpArg),
                context.postfixNameList,
                elementPosition(context)
        )
    }

    fun visitExpr(context: VcExpr?): Surrogate.Expression = when (context) {
        is VcArrExpr -> visitArr(context)
        is VcPiExpr -> visitPi(context)
        is VcSigmaExpr -> visitSigma(context)
        is VcLamExpr -> visitLam(context)
        is VcLetExpr -> visitLet(context)
        is VcCaseExpr -> visitCase(context)
        is VcBinOpExpr -> visitBinOp(context)
        else -> {
            val childExpr = context?.childOfType<VcExpr>()
            childExpr?.let { return visitExpr(it) }
            throw IllegalStateException()
        }
    }

    fun visitArr(context: VcArrExpr): Surrogate.PiExpression {
        val domain = visitExpr(context.exprList[0])
        val codomain = visitExpr(context.exprList[1])
        val arguments = listOf(Surrogate.TypeParameter(domain.position, true, domain))
        return Surrogate.PiExpression(elementPosition(context.arrow), arguments, codomain)
    }

    fun visitBinOp(context: VcBinOpExpr): Surrogate.Expression {
        val newExpr = context.newExpr
        val position = elementPosition(context)
        val implementations = parseImplementations(
                newExpr.withNewContext,
                newExpr.implementStatements,
                position,
                visitBinOpArg(newExpr.binOpArg)
        )
        return parseBinOpSequence(
                context.binOpLeftList,
                implementations,
                context.newExpr.postfixNameList,
                position
        )
    }

    fun visitPi(context: VcPiExpr): Surrogate.PiExpression {
        return Surrogate.PiExpression(
                elementPosition(context),
                visitTeles(context.teleList),
                visitExpr(context.expr)
        )
    }

    fun visitSigma(context: VcSigmaExpr): Surrogate.SigmaExpression {
        val args = visitTeles(context.teleList)
        args.forEach {
            if (!it.explicit) {
                reportError(it.position, "Fields in sigma types must be explicit")
            }
        }
        return Surrogate.SigmaExpression(elementPosition(context), args)
    }

    fun visitLam(context: VcLamExpr): Surrogate.Expression {
        return Surrogate.LamExpression(
                elementPosition(context),
                visitLamTeles(context.teleList),
                visitExpr(context.expr)
        )
    }

    fun visitLet(context: VcLetExpr): Surrogate.LetExpression {
        val clauses = context.letClauseList.map { visitLetClause(it) }
        return Surrogate.LetExpression(elementPosition(context), clauses, visitExpr(context.expr))
    }

    fun visitCase(context: VcCaseExpr): Surrogate.Expression {
        val elimExprs = context.expr0List.map { visitExpr0(it) }
        val clauses = context.clauseList.map { visitClause(it) }
        return Surrogate.CaseExpression(elementPosition(context), elimExprs, clauses)
    }

    fun visitClauses(context: VcClauses): List<Surrogate.FunctionClause> =
            context.clauseList.map { visitClause(it) }

    fun visitLetClause(context: VcLetClause): Surrogate.LetClause {
        val name = context.identifier.text
        val arguments = visitLamTeles(context.teleList)
        val resultType = context.typeAnnotation?.let { visitExpr(it.expr) }
        return Surrogate.LetClause(
                elementPosition(context),
                name,
                arguments,
                resultType,
                visitExpr(context.expr)
        )
    }

    fun visitClause(context: VcClause): Surrogate.FunctionClause {
        val patterns = context.patternList.map { visitPattern(it) }
        val expression = context.expr?.let { visitExpr(it) }
        return Surrogate.FunctionClause(elementPosition(context), patterns, expression)
    }

    fun visitLevelExpr(context: VcLevelExpr): Surrogate.LevelExpression? {
        context.atomLevelExpr?.let { return visitAtomLevelExpr(it) }
        context.maxLevelExpr?.let { return visitMaxLevelExpr(it) }
        context.sucLevelExpr?.let { return visitSucLevelExpr(it) }
        throw IllegalStateException()
    }

    fun visitAtomLevelExpr(context: VcAtomLevelExpr?): Surrogate.LevelExpression? {
        context?.lpKw?.let { return Surrogate.PLevelExpression(elementPosition(context)) }
        context?.lhKw?.let { return Surrogate.HLevelExpression(elementPosition(context)) }
        context?.number?.let {
            val number = Integer.parseInt(it.text)
            return Surrogate.NumberLevelExpression(elementPosition(it), number)
        }
        context?.levelExpr?.let { return visitLevelExpr(it) }
        throw IllegalStateException()
    }

    fun visitSucLevelExpr(context: VcSucLevelExpr): Surrogate.SucLevelExpression {
        return Surrogate.SucLevelExpression(
                elementPosition(context),
                visitAtomLevelExpr(context.atomLevelExpr)
        )
    }

    fun visitMaxLevelExpr(context: VcMaxLevelExpr): Surrogate.MaxLevelExpression {
        return Surrogate.MaxLevelExpression(
                elementPosition(context),
                visitAtomLevelExpr(context.atomLevelExprList[0]),
                visitAtomLevelExpr(context.atomLevelExprList[1])
        )
    }

    fun visitBinOpArg(context: VcBinOpArg?): Surrogate.Expression {
        context?.argumentBinOp?.let { return visitBinOpArgument(it) }
        context?.universeBinOp?.let { return visitUniverse(it) }
        context?.setUniverseBinOp?.let { return visitSetUniverse(it) }
        context?.truncatedUniverseBinOp?.let { return visitTruncatedUniverse(it) }
        throw IllegalStateException()
    }

    fun visitBinOpArgument(context: VcArgumentBinOp): Surrogate.Expression =
            visitArguments(visitAtomFieldsAcc(context.atomFieldsAcc), context.argumentList)

    fun visitUniverse(context: VcUniverseBinOp): Surrogate.UniverseExpression {
        val text = context.universe.text.substring("\\Type".length)

        var lp: Surrogate.LevelExpression? = null
        if (text.isNotEmpty()) {
            val position = elementPosition(context.universe)
            val number = Integer.parseInt(text)
            lp = Surrogate.NumberLevelExpression(position, number)
        }

        var lh: Surrogate.LevelExpression? = null
        if (context.atomLevelExprList.size >= 1) {
            val firstExpr = context.atomLevelExprList[0]
            val level0 = visitAtomLevelExpr(firstExpr)
            if (lp == null) {
                lp = level0
            } else {
                lh = level0
            }

            if (context.atomLevelExprList.size >= 2) {
                val secondExpr = context.atomLevelExprList[1]
                val level1 = visitAtomLevelExpr(secondExpr)
                if (lh == null) {
                    lh = level1
                } else {
                    reportError(elementPosition(secondExpr), "h-level is already specified")
                }
            }
        }

        return Surrogate.UniverseExpression(elementPosition(context), lp, lh)
    }

    fun visitSetUniverse(context: VcSetUniverseBinOp): Surrogate.UniverseExpression {
        val text = context.set.text.substring("\\Set".length)
        val pLevel: Surrogate.LevelExpression? = if (text.isEmpty()) {
            context.atomLevelExpr?.let { visitAtomLevelExpr(it) }
        } else {
            if (context.atomLevelExpr != null) {
                reportError(elementPosition(context.atomLevelExpr), "p-level is already specified")
            }
            val number = Integer.parseInt(text)
            Surrogate.NumberLevelExpression(elementPosition(context.set), number)
        }
        val position = elementPosition(context)
        val numberLevel = Surrogate.NumberLevelExpression(position, 0)
        return Surrogate.UniverseExpression(position, pLevel, numberLevel)
    }

    fun visitTruncatedUniverse(context: VcTruncatedUniverseBinOp): Surrogate.UniverseExpression {
        val text = context.truncatedUniverse.text.let {
            it.substring(it.indexOf('-') + "-Type".length)
        }
        val pLevel: Surrogate.LevelExpression? = if (text.isEmpty()) {
            context.atomLevelExpr?.let { visitAtomLevelExpr(it) }
        } else {
            if (context.atomLevelExpr != null) {
                reportError(elementPosition(context.atomLevelExpr), "p-level is already specified")
            }
            val number = Integer.parseInt(text)
            Surrogate.NumberLevelExpression(elementPosition(context.truncatedUniverse), number)
        }
        val truncatedUniverse = parseTruncatedUniverse(context.truncatedUniverse)
        return Surrogate.UniverseExpression(elementPosition(context), pLevel, truncatedUniverse)
    }

    private fun parseBinOpSequence(
            context: List<VcBinOpLeft>,
            expression: Surrogate.Expression,
            postfixContexts: List<VcPostfixName>,
            position: Surrogate.Position
    ): Surrogate.Expression {
        var left: Surrogate.Expression? = null
        var binOp: Surrogate.ReferenceExpression? = null
        val sequence = mutableListOf<Abstract.BinOpSequenceElem>()
        for (leftContext in context) {
            val newExpr = leftContext.newExpr
            val expr = parseImplementations(
                    newExpr.withNewContext,
                    newExpr.implementStatements,
                    position,
                    visitBinOpArg(newExpr.binOpArg)
            )

            if (left == null) {
                left = expr
            } else {
                sequence.add(Abstract.BinOpSequenceElem(binOp!!, expr))
            }

            leftContext.newExpr.postfixNameList
                    .map { Surrogate.ReferenceExpression(elementPosition(it), null, visitPostfix(it)) }
                    .mapTo(sequence) { Abstract.BinOpSequenceElem(it, null) }

            val name = visitInfix(leftContext.infixName)
            binOp = Surrogate.ReferenceExpression(elementPosition(leftContext.infixName), null, name)
        }

        if (left == null) {
            left = expression
        } else {
            sequence.add(Abstract.BinOpSequenceElem(binOp!!, expression))
        }

        postfixContexts
                .map { Surrogate.ReferenceExpression(elementPosition(it), null, visitPostfix(it)) }
                .mapTo(sequence) { Abstract.BinOpSequenceElem(it, null) }

        return if (sequence.isNotEmpty()) {
            Surrogate.BinOpSequenceExpression(position, left, sequence)
        } else {
            left
        }
    }

    fun visitModulePath(context: VcModuleName): List<String> =
            context.modulePath.text.split("::").filter { it.isNotEmpty() }

    fun visitAtom(expr: VcAtom): Surrogate.Expression {
        expr.atomModuleCall?.let { return visitAtomModuleCall(it) }
        expr.literal?.let { return visitLiteral(it) }
        expr.tuple?.let { return visitTuple(it) }
        expr.number?.let { return visitAtomNumber(it) }
        throw IllegalStateException()
    }

    fun visitAtomModuleCall(context: VcAtomModuleCall): Surrogate.ModuleCallExpression {
        return Surrogate.ModuleCallExpression(
                elementPosition(context),
                visitModulePath(context.moduleName)
        )
    }

    fun visitTuple(context: VcTuple): Surrogate.Expression {
        return if (context.exprList.size == 1) {
            visitExpr(context.exprList.first())
        } else {
            val fields = context.exprList.map { visitExpr(it) }
            Surrogate.TupleExpression(elementPosition(context), fields)
        }
    }

    fun visitAtomNumber(context: PsiElement): Surrogate.NumericLiteral {
        val number = Integer.parseInt(context.text)
        return Surrogate.NumericLiteral(elementPosition(context), number)
    }

    fun visitAtomFieldsAcc(context: VcAtomFieldsAcc?): Surrogate.Expression {
        context ?: throw IllegalStateException()
        var expr = visitAtom(context.atom)
        for (acc in context.fieldAccList) {
            expr = if (acc.identifier != null) {
                Surrogate.ReferenceExpression(
                        elementPosition(acc),
                        expr,
                        acc.identifier?.text
                )
            } else if (acc.number != null) {
                val field = Integer.parseInt(acc.number?.text) - 1
                Surrogate.ProjExpression(
                        elementPosition(acc),
                        expr,
                        field
                )
            } else {
                throw IllegalStateException()
            }
        }
        return expr
    }

    private fun parseImplementations(
            withNewContext: Boolean,
            context: VcImplementStatements?,
            position: Surrogate.Position,
            expr: Surrogate.Expression
    ): Surrogate.Expression {
        var implementations = expr

        if (context != null) {
            val implementStatements = context.implementStatementList.map {
                Surrogate.ClassFieldImpl(
                        elementPosition(it.identifier),
                        it.identifier.text,
                        visitExpr(it.expr)
                )
            }
            implementations = Surrogate.ClassExtExpression(
                    position,
                    implementations,
                    implementStatements
            )
        }

        if (withNewContext) {
            implementations = Surrogate.NewExpression(position, implementations)
        }

        return implementations
    }

    private fun visitArguments(
            expr: Surrogate.Expression,
            context: List<VcArgument>
    ): Surrogate.Expression {
        var appExpr = expr
        for (arg in context) {
            val expr1 = when {
                arg.atomFieldsAcc != null -> visitAtomFieldsAcc(arg.atomFieldsAcc)
                arg.expr != null -> visitExpr(arg.expr)
                arg.universeAtom != null -> visitUniverseAtom(arg.universeAtom)
                else -> throw IllegalStateException()
            }
            val argumentExpr = Surrogate.Argument(expr1, arg.expr == null)
            appExpr = Surrogate.AppExpression(expr.position, appExpr, argumentExpr)
        }
        return appExpr
    }

    fun visitLiteral(context: VcLiteral): Surrogate.Expression {
        context.prefixName?.let {
            return Surrogate.ReferenceExpression(elementPosition(context), null, visitPrefix(it))
        }
        context.propKw?.let {
            val position = elementPosition(context)
            return Surrogate.UniverseExpression(
                    position,
                    Surrogate.NumberLevelExpression(position, 0),
                    Surrogate.NumberLevelExpression(position, -1)
            )
        }
        context.underscore?.let { return Surrogate.InferHoleExpression(elementPosition(context)) }
        context.goal?.let {
            return Surrogate.GoalExpression(
                    elementPosition(context),
                    it.identifier?.text,
                    it.expr?.let { visitExpr(it) }
            )
        }

        throw IllegalStateException()
    }

    fun visitUniTruncatedUniverse(context: VcUniverseAtom): Surrogate.UniverseExpression {
        val truncatedUniverse = context.truncatedUniverse ?: throw IllegalStateException()
        val text = truncatedUniverse.text.let { it.substring(it.indexOf('-') + "-Type".length) }
        val pLevel = if (text.isNotEmpty()) {
            val number = Integer.parseInt(text)
            Surrogate.NumberLevelExpression(elementPosition(truncatedUniverse), number)
        } else {
            null
        }
        return Surrogate.UniverseExpression(
                elementPosition(context),
                pLevel,
                parseTruncatedUniverse(truncatedUniverse)
        )
    }

    private fun parseTruncatedUniverse(context: PsiElement): Surrogate.LevelExpression {
        val universe = context.text
        if (universe[1] == 'o') {
            return Surrogate.InfLevelExpression(elementPosition(context))
        }
        val number = Integer.parseInt(universe.substring(1, universe.indexOf('-')))
        return Surrogate.NumberLevelExpression(elementPosition(context), number)
    }

    fun visitUniverseAtom(context: VcUniverseAtom?): Surrogate.UniverseExpression {
        context?.set?.let { return visitUniSetUniverse(context) }
        context?.truncatedUniverse?.let { return visitUniTruncatedUniverse(context) }
        context?.universe?.let { return visitUniUniverse(context) }
        throw IllegalStateException()
    }

    fun visitUniUniverse(context: VcUniverseAtom): Surrogate.UniverseExpression {
        val universe = context.universe ?: throw IllegalStateException()
        val text = universe.text.substring("\\Type".length)
        val lp = if (text.isNotEmpty()) {
            val number = Integer.parseInt(text)
            Surrogate.NumberLevelExpression(elementPosition(universe), number)
        } else {
            null
        }
        return Surrogate.UniverseExpression(elementPosition(context), lp, null)
    }

    fun visitUniSetUniverse(context: VcUniverseAtom): Surrogate.UniverseExpression {
        val text = context.set?.text?.substring("\\Set".length) ?: throw IllegalStateException()
        val pLevel = if (text.isNotEmpty()) {
            val number = Integer.parseInt(text)
            Surrogate.NumberLevelExpression(elementPosition(context.set), number)
        } else {
            null
        }
        val position = elementPosition(context)
        return Surrogate.UniverseExpression(
                position,
                pLevel,
                Surrogate.NumberLevelExpression(position, 0)
        )
    }

    private fun visitLamTele(context: VcTele): List<Surrogate.Parameter>? {
        val parameters = mutableListOf<Surrogate.Parameter>()
        if (context.isExplicit || context.isImplicit) {
            val explicit = context.isExplicit
            val typedExpr = context.typedExpr!!
            val varsExpr1 = typedExpr.identifierOrUnknownList
            val varsExpr2 = typedExpr.expr

            val typeExpr = if (typedExpr.hasType) visitExpr(typedExpr.expr) else null
            val vars = if (typedExpr.hasType) varsExpr1.map { getVar(it) } else getVars(varsExpr2)

            if (typeExpr == null) {
                vars.mapTo(parameters) { Surrogate.NameParameter(it?.position, explicit, it?.name) }
            } else {
                val parameter = Surrogate.TelescopeParameter(
                        elementPosition(context),
                        explicit,
                        vars,
                        typeExpr
                )
                parameters.add(parameter)
            }
        } else {
            var ok = context.literal != null
            if (ok) {
                val literalContext = context.literal
                if (literalContext?.prefixName != null || literalContext?.underscore != null) {
                    val name = literalContext.prefixName?.let { visitPrefix(it) }
                    val parameter = Surrogate.NameParameter(
                            elementPosition(literalContext),
                            true,
                            name
                    )
                    parameters.add(parameter)
                } else {
                    ok = false
                }
            }
            if (!ok) {
                reportError(elementPosition(context), "Unexpected token, expected an identifier")
                throw ParseException()
            }
        }
        return parameters
    }

    private fun visitLamTeles(context: List<VcTele>): List<Surrogate.Parameter> =
            context.map { visitLamTele(it) }.filterNotNull().flatten()

    private fun visitTeles(context: List<VcTele>): List<Surrogate.TypeParameter> {
        val parameters = mutableListOf<Surrogate.TypeParameter>()
        for (tele in context) {
            val explicit = !tele.isImplicit
            var typedExpr: VcTypedExpr?
            if (explicit) {
                if (tele.isExplicit) {
                    typedExpr = tele.typedExpr
                } else if (tele.literal != null) {
                    val literal = tele.literal?.let { visitLiteral(it) }
                    parameters.add(Surrogate.TypeParameter(true, literal))
                    continue
                } else if (tele.universeAtom != null) {
                    val universeAtom = tele.universeAtom?.let { visitUniverseAtom(it) }
                    parameters.add(Surrogate.TypeParameter(true, universeAtom))
                    continue
                } else {
                    throw IllegalStateException()
                }
            } else {
                typedExpr = tele.typedExpr
            }

            if (typedExpr != null && typedExpr.hasType) {
                val vars = typedExpr.identifierOrUnknownList.map { getVar(it) }
                val parameter = Surrogate.TelescopeParameter(
                        elementPosition(tele),
                        explicit,
                        vars,
                        visitExpr(typedExpr.expr)
                )
                parameters.add(parameter)
            } else {
                val parameter = Surrogate.TypeParameter(explicit, visitExpr(typedExpr?.expr))
                parameters.add(parameter)
            }
        }
        return parameters
    }

    private fun visitFunctionArguments(context: List<VcTele>): List<Surrogate.Parameter> {
        val arguments = mutableListOf<Surrogate.Parameter>()
        for (tele in context) {
            val args = visitLamTele(tele)
            if (args != null && args.isNotEmpty()) {
                if (args.first() is Surrogate.TelescopeParameter) {
                    arguments.add(args.first())
                } else {
                    reportError(elementPosition(tele), "Expected a typed variable")
                }
            }
        }
        return arguments
    }

    // Utils

    private fun elementPosition(element: PsiElement?): Surrogate.Position {
        val definition = element?.parentOfType<DefinitionAdapter>() ?: element?.containingFile
        return Surrogate.Position(module, definition)
    }

    private fun getVar(context: VcAtomFieldsAcc): Surrogate.LocalVariable? =
            if (context.fieldAccList.isEmpty()) getVar(context.atom.literal) else null

    private fun getVar(context: VcLiteral?): Surrogate.LocalVariable? {
        context?.prefixName?.let { return Surrogate.LocalVariable(elementPosition(it), it.text) }
        context?.underscore?.let { return Surrogate.LocalVariable(elementPosition(it), null) }
        throw IllegalStateException()
    }

    private fun getVar(context: VcIdentifierOrUnknown): Surrogate.LocalVariable? =
            Surrogate.LocalVariable(elementPosition(context), context.text)

    private fun getVarsNull(context: VcExpr): List<Surrogate.LocalVariable>? {
        if (context !is VcBinOpExpr
                || context.binOpLeftList.isNotEmpty()
                || context.newExpr.binOpArg?.argumentBinOp == null
                || context.newExpr.withNewContext
                || context.newExpr.implementStatements != null
                ) {
            return null
        }

        val argumentBinOp = context.newExpr.binOpArg?.argumentBinOp ?: return null
        val firstArg = argumentBinOp.atomFieldsAcc.let { getVar(it) } ?: return null
        val result = mutableListOf(firstArg)
        for (argument in argumentBinOp.argumentList) {
            if (argument.atomFieldsAcc != null) {
                val arg = argument.atomFieldsAcc?.let { getVar(it) } ?: return null
                result.add(arg)
            } else if (argument.expr != null) {
                val arguments = argument.expr?.let { getVarsNull(it) } ?: return null
                result.addAll(arguments)
            } else {
                return null
            }
        }
        return result
    }

    private fun getVars(context: VcExpr): List<Surrogate.LocalVariable> {
        val result = getVarsNull(context)
        result?.let { return it }
        reportError(elementPosition(context), "Expected a list of variables")
        throw ParseException()
    }

    private fun visitPrefix(prefix: VcPrefixName): String {
        prefix.prefix?.let { return it.text }
        prefix.prefixInfix?.let { return it.text.drop(1) }
        throw IllegalStateException()
    }

    private fun visitInfix(infix: VcInfixName): String {
        infix.infix?.let { return it.text }
        infix.infixPrefix?.let { return it.text.drop(1) }
        throw IllegalStateException()
    }

    private fun visitPostfix(postfix: VcPostfixName): String {
        postfix.postfixInfix?.let { return it.text.dropLast(1) }
        postfix.postfixPrefix?.let { return it.text.dropLast(1) }
        throw IllegalStateException()
    }

    // Errors

    val MISPLACES_DEFINITION = "This definition is not allowed here"

    private fun reportError(position: Surrogate.Position, message: String) {
        val concretePosition = Concrete.Position(position.module, 0, 0)
        val error = ParserError(concretePosition, message)
        errorReporter.report(error)
    }
}