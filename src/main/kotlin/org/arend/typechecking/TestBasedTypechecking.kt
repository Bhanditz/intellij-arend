package org.arend.typechecking

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import org.arend.core.definition.Definition
import org.arend.naming.reference.TCReferable
import org.arend.psi.ArendFile
import org.arend.psi.ext.PsiLocatedReferable
import org.arend.resolving.DataLocatedReferable
import org.arend.typechecking.error.TypecheckingErrorReporter
import org.arend.typechecking.execution.FullModulePath
import org.arend.typechecking.execution.PsiElementComparator
import org.arend.typechecking.execution.TypecheckingEventsProcessor
import org.arend.typechecking.order.dependency.DependencyListener
import org.arend.typechecking.order.listener.TypecheckingOrderingListener
import org.arend.typechecking.typecheckable.provider.ConcreteProvider


class TestBasedTypechecking(
    private val eventsProcessor: TypecheckingEventsProcessor,
    instanceProviderSet: PsiInstanceProviderSet,
    state: TypecheckerState,
    concreteProvider: ConcreteProvider,
    private val errorReporter: TypecheckingErrorReporter,
    dependencyListener: DependencyListener)
    : TypecheckingOrderingListener(instanceProviderSet, state, concreteProvider, errorReporter, dependencyListener, PsiElementComparator) {

    val typecheckedModules = LinkedHashSet<FullModulePath>()
    val typecheckedFiles = LinkedHashSet<SmartPsiElementPointer<ArendFile>>()

    private fun startTimer(definition: TCReferable) {
        val psiPtr = (definition as? DataLocatedReferable)?.data ?: return
        (runReadAction { psiPtr.element } as? PsiLocatedReferable)?.let { eventsProcessor.startTimer(it) }
    }

    private fun stopTimer(definition: TCReferable) {
        val psiPtr = (definition as? DataLocatedReferable)?.data ?: return
        (runReadAction { psiPtr.element } as? PsiLocatedReferable)?.let { eventsProcessor.stopTimer(it) }
    }

    override fun typecheckingUnitStarted(definition: TCReferable) {
        startTimer(definition)
    }

    override fun typecheckingUnitFinished(referable: TCReferable, definition: Definition) {
        errorReporter.flush()

        val ref = PsiLocatedReferable.fromReferable(referable) ?: return
        eventsProcessor.stopTimer(ref)
        if (definition.status() != Definition.TypeCheckingStatus.NO_ERRORS) {
            val status = when {
                !definition.status().headerIsOK() -> Definition.TypeCheckingStatus.HEADER_HAS_ERRORS
                definition.status() == Definition.TypeCheckingStatus.HAS_WARNINGS || definition.status() == Definition.TypeCheckingStatus.MAY_BE_TYPE_CHECKED_WITH_WARNINGS -> Definition.TypeCheckingStatus.MAY_BE_TYPE_CHECKED_WITH_WARNINGS
                else -> Definition.TypeCheckingStatus.MAY_BE_TYPE_CHECKED_WITH_ERRORS
            }
            definition.setStatus(status)
            eventsProcessor.onTestFailure(ref)
        }
        eventsProcessor.onTestFinished(ref)
        runReadAction {
            val file = ref.containingFile as? ArendFile ?: return@runReadAction
            typecheckedModules.add(FullModulePath(file.libraryName ?: return@runReadAction, file.modulePath ?: return@runReadAction))
            typecheckedFiles.add(SmartPointerManager.createPointer(file))
        }
    }

    override fun typecheckingHeaderStarted(definition: TCReferable) {
        startTimer(definition)
    }

    override fun typecheckingHeaderFinished(referable: TCReferable, definition: Definition?) {
        stopTimer(referable)
    }

    override fun typecheckingBodyStarted(definition: TCReferable) {
        startTimer(definition)
    }

    override fun typecheckingBodyFinished(referable: TCReferable, definition: Definition) {
        typecheckingUnitFinished(referable, definition)
    }

    override fun typecheckingInterrupted(definition: TCReferable) {
        val ref = PsiLocatedReferable.fromReferable(definition) ?: return
        eventsProcessor.stopTimer(ref)
        eventsProcessor.onTestFailure(ref, true)
        eventsProcessor.onTestFinished(ref)
    }
}