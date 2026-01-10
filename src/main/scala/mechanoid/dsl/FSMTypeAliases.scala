package mechanoid.dsl

import mechanoid.core.*

/** ZIO-style type aliases for FSMDefinition.
  *
  * These provide convenient shortcuts for common FSM configurations:
  *   - UFSM: Pure FSM with no environment or errors
  *   - URFSM: FSM with environment but no errors
  *   - TaskFSM: FSM with Throwable errors
  *   - RFSM: FSM with environment and Throwable errors
  *   - IOFSM: FSM with custom errors
  */

/** Pure FSM - no environment, no errors */
type UFSM[S <: MState, E <: MEvent] = FSMDefinition[S, E, Any, Nothing]
object UFSM:
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum]: UFSM[S, E] =
    FSMDefinition[S, E, Any, Nothing]

/** FSM with environment, no errors */
type URFSM[S <: MState, E <: MEvent, R] = FSMDefinition[S, E, R, Nothing]
object URFSM:
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum, R]: URFSM[S, E, R] =
    FSMDefinition[S, E, R, Nothing]

/** FSM with Throwable errors, no environment */
type TaskFSM[S <: MState, E <: MEvent] = FSMDefinition[S, E, Any, Throwable]
object TaskFSM:
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum]: TaskFSM[S, E] =
    FSMDefinition[S, E, Any, Throwable]

/** FSM with environment and Throwable errors */
type RFSM[S <: MState, E <: MEvent, R] = FSMDefinition[S, E, R, Throwable]
object RFSM:
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum, R]: RFSM[S, E, R] =
    FSMDefinition[S, E, R, Throwable]

/** FSM with custom errors, no environment */
type IOFSM[S <: MState, E <: MEvent, Err] = FSMDefinition[S, E, Any, Err]
object IOFSM:
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum, Err]: IOFSM[S, E, Err] =
    FSMDefinition[S, E, Any, Err]
