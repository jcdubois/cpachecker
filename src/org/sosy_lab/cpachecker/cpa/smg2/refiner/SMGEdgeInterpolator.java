// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cpa.smg2.refiner;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cpa.arg.path.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.path.PathIterator;
import org.sosy_lab.cpachecker.cpa.arg.path.PathPosition;
import org.sosy_lab.cpachecker.cpa.smg2.SMGInformation;
import org.sosy_lab.cpachecker.cpa.smg2.SMGOptions;
import org.sosy_lab.cpachecker.cpa.smg2.SMGState;
import org.sosy_lab.cpachecker.cpa.value.ValueAnalysisCPA;
import org.sosy_lab.cpachecker.cpa.value.type.Value;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.refinement.FeasibilityChecker;
import org.sosy_lab.cpachecker.util.refinement.GenericEdgeInterpolator;
import org.sosy_lab.cpachecker.util.refinement.InterpolantManager;
import org.sosy_lab.cpachecker.util.refinement.StrongestPostOperator;
import org.sosy_lab.cpachecker.util.states.MemoryLocation;

@Options(prefix = "cpa.smg2.interpolation")
public class SMGEdgeInterpolator
    extends GenericEdgeInterpolator<SMGState, SMGInformation, SMGInterpolant> {

  /** This method acts as the constructor of the class. */
  public SMGEdgeInterpolator(
      final FeasibilityChecker<SMGState> pFeasibilityChecker,
      final StrongestPostOperator<SMGState> pStrongestPostOperator,
      final Configuration pConfig,
      final ShutdownNotifier pShutdownNotifier,
      final CFA pCfa,
      final LogManager pLogger)
      throws InvalidConfigurationException {

    super(
        pStrongestPostOperator,
        pFeasibilityChecker,
        SMGInterpolantManager.getInstance(
            new SMGOptions(pConfig), pCfa.getMachineModel(), pLogger, pCfa),
        SMGState.of(pCfa.getMachineModel(), pLogger, new SMGOptions(pConfig), pCfa),
        ValueAnalysisCPA.class,
        pConfig,
        pShutdownNotifier,
        pCfa);
  }

  /**
   * This method derives an interpolant for the given error path and interpolation state.
   *
   * @param pErrorPath the path to check
   * @param pCurrentEdge the current edge to interpolate
   * @param pCallstack the current call stack
   * @param pOffset offset of the state at where to start the current interpolation
   * @param pInputInterpolant the input interpolant
   */
  @SuppressWarnings("unchecked")
  @Override
  public SMGInterpolant deriveInterpolant(
      final ARGPath pErrorPath,
      final CFAEdge pCurrentEdge,
      final Deque<SMGState> pCallstack,
      final PathPosition pOffset,
      final SMGInterpolant pInputInterpolant)
      throws CPAException, InterruptedException {

    super.resetNumberOfInterpolationQueries();
    InterpolantManager<SMGState, SMGInterpolant> interpolantMgr = getInterpolationManager();

    // create initial state, based on input interpolant, and create initial successor by consuming
    // the next edge
    SMGState stateFromOldInterpolant = pInputInterpolant.reconstructState();

    // TODO callstack-management depends on a forward-iteration on a single path.
    // TODO Thus interpolants have to be computed from front to end. Can we assure this?
    final Optional<SMGState> maybeSuccessor;
    if (pCurrentEdge == null) {
      PathIterator it = pOffset.fullPathIterator();
      Optional<SMGState> intermediate = Optional.of(stateFromOldInterpolant);
      do {
        if (!intermediate.isPresent()) {
          break;
        }

        intermediate =
            getInitialSuccessor(intermediate.orElseThrow(), it.getOutgoingEdge(), pCallstack);
        it.advance();
      } while (!it.isPositionWithState());
      maybeSuccessor = intermediate;
    } else {
      maybeSuccessor = getInitialSuccessor(stateFromOldInterpolant, pCurrentEdge, pCallstack);
    }

    if (!maybeSuccessor.isPresent()) {
      return interpolantMgr.getFalseInterpolant();
    }

    SMGState initialSuccessor = maybeSuccessor.orElseThrow();

    // if initial state and successor are equal, return the input interpolant
    // in general, this returned interpolant might be stronger than needed, but only in very rare
    // cases, the weaker interpolant would be different from the input interpolant, so we spare the
    // effort
    if (getApplyItpEqualityOptimization() && stateFromOldInterpolant.equals(initialSuccessor)) {
      return pInputInterpolant;
    }

    // if the current edge just changes the names of variables
    // (e.g. function arguments, returned variables)
    // then return the input interpolant with those renamings
    if (getApplyRenamingOptimization() && isOnlyVariableRenamingEdge(pCurrentEdge)) {
      return interpolantMgr.createInterpolant(initialSuccessor);
    }

    ARGPath remainingErrorPath = pOffset.iterator().getSuffixExclusive();

    // if the remaining path, i.e., the suffix, is contradicting by itself, then return the TRUE
    // interpolant
    if (getApplyUnsatSuffixOptimization()
        && pInputInterpolant.isTrue()
        && initialSuccessor.getSize() > 1
        && isSuffixContradicting(remainingErrorPath, initialSuccessor)) {
      // Remember stack frame information!
      return interpolantMgr.getTrueInterpolant().addStackFrameInformationAndCopy(initialSuccessor);
    }

    for (MemoryLocation currentMemoryLocation :
        determineMemoryLocationsToInterpolateOn(initialSuccessor)) {
      getShutdownNotifier().shutdownIfNecessary();

      // temporarily remove the value of the current memory location from the candidate
      // interpolant
      // Immutable copyOnOperation case
      SMGState oldSuccessor = initialSuccessor;
      initialSuccessor =
          initialSuccessor
              .copyAndForget(currentMemoryLocation)
              .getState()
              .copyAndPruneUnreachable();

      // check if the remaining path now becomes feasible
      if (isRemainingPathFeasible(remainingErrorPath, initialSuccessor)) {
        // Since we use immutable states, we can just use the old state
        initialSuccessor = oldSuccessor;
      }
    }
    // TODO: make this generic
    Set<Value> heapValues = initialSuccessor.getTrackedHeapValues();
    for (Value heapValue : heapValues) {
      getShutdownNotifier().shutdownIfNecessary();

      SMGState oldState = initialSuccessor;
      // temporarily remove a value from the heap and re-add only if it is needed
      initialSuccessor = initialSuccessor.removeHeapValue(heapValue);

      // check if the remaining path now becomes feasible
      if (isRemainingPathFeasible(remainingErrorPath, initialSuccessor)) {
        initialSuccessor = oldState;
      }
    }
    SMGInterpolant newInterpolant = interpolantMgr.createInterpolant(initialSuccessor);
    // Check that no variable was added to a wrong stack frame
    assert newInterpolant.isSanityIntact();
    // TODO: remove or make an assert after its made sure it does not trigger!
    Preconditions.checkArgument(newInterpolant.isSanityIntact());

    return newInterpolant;
  }

  /**
   * This method checks, if the given error path is contradicting in itself. This NEEDS the function
   * stack to work properly! (not the values, just the definition to create the stack)
   *
   * @param errorPath the error path to check.
   * @return true, if the given error path is contradicting in itself, else false
   */
  private boolean isSuffixContradicting(ARGPath errorPath, SMGState stateForFrameInfo)
      throws CPAException, InterruptedException {
    return !isRemainingPathFeasible(
        errorPath,
        getInitalState()
            .reconstructSMGStateFromNonHeapAssignments(
                null,
                null,
                null,
                stateForFrameInfo.getMemoryModel().getFunctionDeclarationsFromStackFrames(),
                ImmutableSet.of()));
  }
}
