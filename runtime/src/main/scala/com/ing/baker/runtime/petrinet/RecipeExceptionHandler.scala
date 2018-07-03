package com.ing.baker.runtime.petrinet

import akka.event.EventStream
import com.ing.baker.il.failurestrategy.ExceptionStrategyOutcome
import com.ing.baker.il.petrinet.{InteractionTransition, Place, Transition}
import com.ing.baker.petrinet.api.MultiSet
import com.ing.baker.petrinet.runtime.ExceptionStrategy.{BlockTransition, Continue, RetryWithDelay}
import com.ing.baker.petrinet.runtime.{ExceptionHandler, Job}
import com.ing.baker.runtime.core.{FatalInteractionException, ProcessState, RuntimeEvent}
import com.ing.baker.runtime.core.events.InteractionFailed

/**
  * This class deals with exceptions thrown in the execution of a recipe.
  */
class RecipeExceptionHandler(recipeName: String, eventStream: EventStream) extends ExceptionHandler[Place, Transition, ProcessState] {

  override def handleException(job: Job[Place, Transition, ProcessState])
                              (throwable: Throwable, failureCount: Int, startTime: Long, outMarking: MultiSet[Place[_]]) = {

    def isFatal(throwable: Throwable) = {
      throwable.isInstanceOf[Error] || throwable.isInstanceOf[FatalInteractionException]
    }

    job.transition match {
      case interaction: InteractionTransition[_] =>

        // compute the interaction failure strategy outcome
        val failureStrategyOutcome =
          if (isFatal(throwable))
            ExceptionStrategyOutcome.BlockTransition
          else
            interaction.failureStrategy.apply(failureCount)

        val currentTime = System.currentTimeMillis()

        eventStream.publish(
          InteractionFailed(currentTime, currentTime - startTime, recipeName,
            job.processState.processId, job.transition.label, failureCount, throwable, failureStrategyOutcome))

        // translates the recipe failure strategy to a petri net failure strategy
        failureStrategyOutcome match {
          case ExceptionStrategyOutcome.BlockTransition => BlockTransition
          case ExceptionStrategyOutcome.RetryWithDelay(delay) => RetryWithDelay(delay)
          case ExceptionStrategyOutcome.Continue(eventName) => {
            val runtimeEvent = new RuntimeEvent(eventName, Seq.empty)
            Continue(createProducedMarking(interaction, outMarking)(runtimeEvent), runtimeEvent)
          }
        }

      case _ => BlockTransition
    }
  }
}
