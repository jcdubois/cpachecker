// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2023 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.core.algorithm.bmc;
import static org.sosy_lab.cpachecker.util.statistics.StatisticsWriter.writingStatisticsTo;

import com.google.common.collect.ImmutableList;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.reachedset.AggregatedReachedSets;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSetFactory;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.core.specification.Specification;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateAbstractionManager;
import org.sosy_lab.cpachecker.cpa.predicate.PredicateCPA;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.CPAs;
import org.sosy_lab.cpachecker.util.predicates.interpolation.InterpolationManager;
import org.sosy_lab.cpachecker.util.predicates.pathformula.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.smt.BooleanFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.SolverException;

/**
 * This class provides implementation of dual approximated reachability model checking
 * algorithm (DAR) adapted for program verification.
 *<p>
 * The original DAR algorithm was proposed in the paper "Intertwined Forward-Backward Reachability
 * Analysis Using Interpolants" from Y. Vizel, O. Grumberg and S. Shoham. The algorithm computes
 * two interpolant sequences - Forward (FRS) and Backward (BRS). FRS is initialized with initial
 * states formula and BRS with formula describing states that violate specification. The idea is
 * that FRS overapproximates reachability vector of states reachable from initial states,
 * on the other hand BRS overapproximates states that can reach violating states. In each iteration
 * the algorithm performs two phases - Local and Global strengthening. Let FRS = F0,F1,F2...,Fn
 * and BRS = B0,B1,B2...,Bn, the Local strengthening phase checks if Fi ∧ TR ∧ Bj is unsatisfiable,
 * if yes, then there is no counterexample of length n+1. In such case, it propagates the
 * "reason of unsatisfiability" via interpolants up to Fn+1, Bn+1 and proceeds into another
 * iteration. If no such (i,j) is found, it switches to Global strengthening phase. It performs BMC
 * and iteratively unrolls formula INIT ∧ TR ∧ ... ∧ TR ∧ Bn-i to check for satisfiability.
 * If some of the formulae is unsatisfiable, it creates interpolation sequence and strengthens
 * F0,...,Fi. If all of the formulae are satisfiable, BMC finds a counterexample.
 *<p/>
 */

@Options(prefix = "dar")
public class DARAlgorithm extends AbstractBMCAlgorithm implements Algorithm {
  @Option(
      secure = true,
      description =
          "toggle which strategy is used for computing fixed points in order to verify programs"
              + " with loops. If it is not set to true, DAR is not used.")
  private boolean isDAREnabled = true;

  @Option(
      secure = true,
      description = "toggle falling back if interpolation or forward-condition is disabled")
  private boolean fallBack = true;

  @Option(secure = true, description = "toggle checking whether the safety property is inductive")
  private boolean checkPropertyInductiveness = false;

  @Option(secure = true, description = "toggle asserting targets at every iteration for DAR")
  private boolean assertTargetsAtEveryIteration = false;

  private final ConfigurableProgramAnalysis cpa;

  private final Algorithm algorithm;

  private final PathFormulaManager pfmgr;
  private final FormulaManagerView fmgr;
  private final BooleanFormulaManagerView bfmgr;
  private final Solver solver;
  private final PredicateAbstractionManager predAbsMgr;
  private final InterpolationManager itpMgr;
  private final CFA cfa;

  private BooleanFormula finalFixedPoint;

  //TODO: Extend as needed, when implementing
  public DARAlgorithm(
      Algorithm pAlgorithm,
      ConfigurableProgramAnalysis pCPA,
      Configuration pConfig,
      LogManager pLogger,
      ReachedSetFactory pReachedSetFactory,
      ShutdownManager pShutdownManager,
      CFA pCFA,
      final Specification specification,
      AggregatedReachedSets pAggregatedReachedSets)
      throws InvalidConfigurationException, CPAException, InterruptedException {
    super(
        pAlgorithm,
        pCPA,
        pConfig,
        pLogger,
        pReachedSetFactory,
        pShutdownManager,
        pCFA,
        specification,
        new BMCStatistics(),
        false /* no invariant generator */,
        pAggregatedReachedSets);
    pConfig.inject(this);

    cpa = pCPA;
    cfa = pCFA;
    algorithm = pAlgorithm;

    @SuppressWarnings("resource")
    PredicateCPA predCpa = CPAs.retrieveCPAOrFail(cpa, PredicateCPA.class, IMCAlgorithm.class);
    solver = predCpa.getSolver();
    pfmgr = predCpa.getPathFormulaManager();
    predAbsMgr = predCpa.getPredicateManager();
    fmgr = solver.getFormulaManager();
    bfmgr = fmgr.getBooleanFormulaManager();
    itpMgr =
        new InterpolationManager(
            pfmgr, solver, Optional.empty(), Optional.empty(), pConfig,
            shutdownNotifier, logger);

    finalFixedPoint = bfmgr.makeFalse();
  }
  
  @Override
  public AlgorithmStatus run(final ReachedSet pReachedSet)
      throws CPAException, InterruptedException {
    try {
      return dualapproximatedreachabilityModelChecking(pReachedSet);
    } catch (SolverException e) {
      throw new CPAException("Solver Failure " + e.getMessage(), e);
    } finally {
      invariantGenerator.cancel();
    }
  }

  //TODO:
  private AlgorithmStatus dualapproximatedreachabilityModelChecking(ReachedSet pReachedSet)
      throws CPAException, SolverException, InterruptedException {
    if (getTargetLocations().isEmpty()) {
      pReachedSet.clearWaitlist();
      return AlgorithmStatus.SOUND_AND_PRECISE;
    }

    if (!cfa.getAllLoopHeads().isPresent()) {
      if (isDAREnabled) {
        logger.log(
            Level.WARNING, "Disable interpolation as loop structure could not be determined");
        isDAREnabled = false;
      }
      if (checkPropertyInductiveness) {
        logger.log(
            Level.WARNING, "Disable induction check as loop structure could not be determined");
        checkPropertyInductiveness = false;
      }
    }
    if (cfa.getAllLoopHeads().orElseThrow().size() > 1) {
      if (isDAREnabled) {
        if (fallBack) {
          fallBackToBMC("Interpolation is not supported for multi-loop programs yet");
        } else {
          throw new CPAException("Multi-loop programs are not supported yet");
        }
      }
      if (checkPropertyInductiveness) {
        logger.log(
            Level.WARNING, "Disable induction check because the program contains multiple loops");
        checkPropertyInductiveness = false;
      }
    }

    logger.log(Level.FINE, "Performing dual approximated reachability model checking");
    PartitionedFormulas partitionedFormulas =
        new PartitionedFormulas(bfmgr, logger, assertTargetsAtEveryIteration);
    // Initialize FRS to [INIT]
    List<BooleanFormula> forwardReachVector = initializeFRS(partitionedFormulas);
    // Initialize BRS to [~P]
    List<BooleanFormula> backwardReachVector = initializeBRS(partitionedFormulas);
    DualInterpolationSequence dualSequence = new DualInterpolationSequence
        (forwardReachVector, backwardReachVector, false);

    do {
      dualSequence = localStrengtheningPhase(dualSequence, partitionedFormulas);
    } while (adjustConditions());
    return null;
  }

  private List<BooleanFormula> initializeBRS(PartitionedFormulas pPartitionedFormulas) {
    return Collections.singletonList(pPartitionedFormulas.getPrefixFormula());
  }
  private List<BooleanFormula> initializeFRS(PartitionedFormulas pPartitionedFormulas) {
    return Collections.singletonList(pPartitionedFormulas.getAssertionFormula());
  }

  private DualInterpolationSequence localStrengtheningPhase
      (DualInterpolationSequence pDualSequence, PartitionedFormulas pPartitionedFormulas)
      throws CPAException, SolverException, InterruptedException {
    int indexOfLocalContradiction;
    indexOfLocalContradiction = findIndexOfUnsatisfiableLocalCheck
        (pDualSequence, pPartitionedFormulas);
    if (indexOfLocalContradiction == -1) {
      // No local strengthening point was found, switch to Global phase
      pDualSequence.setLocallyUnsafe();
      return pDualSequence;
    }
    // Local strengthening point was found, propagate the reason for contradiction to the end
    // of sequences.
    pDualSequence.setLocallySafe();
    pDualSequence = iterativeLocalStrengthening
        (pDualSequence, pPartitionedFormulas, indexOfLocalContradiction);
    return pDualSequence;
  }
  private DualInterpolationSequence iterativeLocalStrengthening
      (DualInterpolationSequence pDualSequence, PartitionedFormulas pPartitionedFormulas,
       int pIndexOfLocalContradiction)
      throws CPAException, InterruptedException {
    int lastIndexOfSequences = pDualSequence.getForwardReachVector().size() - 1;
    int indexFRS = pIndexOfLocalContradiction;
    int indexBRS = lastIndexOfSequences - pIndexOfLocalContradiction;

    while (indexFRS < lastIndexOfSequences) {
      BooleanFormula resultingForwardFormula = pDualSequence.getForwardReachVector().get(indexFRS + 1);
      BooleanFormula interpolant = constructForwardInterpolant
          (pDualSequence, pPartitionedFormulas, indexFRS);
      resultingForwardFormula = bfmgr.and(resultingForwardFormula, interpolant);
      pDualSequence.updateForwardReachVector(resultingForwardFormula, indexFRS + 1);
      indexFRS++;
    }
    BooleanFormula newForwardReachFormula =  constructForwardInterpolant
        (pDualSequence, pPartitionedFormulas, indexFRS);
    pDualSequence.increaseForwardReachVector(newForwardReachFormula);

    while (indexBRS < lastIndexOfSequences) {
      BooleanFormula resultingBackwardFormula = pDualSequence.getBackwardReachVector().get(indexBRS + 1);
      BooleanFormula interpolant = constructBackwardInterpolant
          (pDualSequence, pPartitionedFormulas, indexBRS);
      resultingBackwardFormula = bfmgr.and(resultingBackwardFormula, interpolant);
      pDualSequence.updateBackwardReachVector(resultingBackwardFormula, indexBRS + 1);
      indexBRS++;
    }
    BooleanFormula newBackwardReachFormula =  constructBackwardInterpolant
        (pDualSequence, pPartitionedFormulas, indexFRS);
    pDualSequence.increaseBackwardReachVector(newBackwardReachFormula);
    return pDualSequence;
  }
  private BooleanFormula constructForwardInterpolant(DualInterpolationSequence pDualSequence,
                                        PartitionedFormulas pPartitionedFormulas, int pIndex)
      throws CPAException, InterruptedException {
    int lastIndexOfSequences = pDualSequence.getForwardReachVector().size() - 1;
    List<BooleanFormula> transitionFormulae = pPartitionedFormulas.getLoopFormulas();
    BooleanFormula forwardFormula = pDualSequence.getForwardReachVector().get(pIndex);
    BooleanFormula backwardFormula = pDualSequence.getBackwardReachVector()
        .get(lastIndexOfSequences - pIndex);

    Optional<ImmutableList<BooleanFormula>> interpolants =
        itpMgr.interpolate(ImmutableList.of(
            bfmgr.and(forwardFormula, transitionFormulae.get(pIndex)), backwardFormula));
    assert interpolants.isPresent();
    assert interpolants.orElseThrow().size() == 2;
    BooleanFormula interpolant = interpolants.orElseThrow().get(1);

    return interpolant;
  }
  private BooleanFormula constructBackwardInterpolant(DualInterpolationSequence pDualSequence,
                                                        PartitionedFormulas pPartitionedFormulas, int pIndex)
      throws CPAException, InterruptedException {
    int lastIndexOfSequences = pDualSequence.getBackwardReachVector().size() - 1;
    List<BooleanFormula> transitionFormulae = pPartitionedFormulas.getLoopFormulas();
    BooleanFormula forwardFormula = pDualSequence.getForwardReachVector()
        .get(lastIndexOfSequences - pIndex);
    BooleanFormula backwardFormula = pDualSequence.getBackwardReachVector().get(pIndex);

    Optional<ImmutableList<BooleanFormula>> interpolants =
        itpMgr.interpolate(ImmutableList.of
            (bfmgr.and(backwardFormula, transitionFormulae.get(pIndex)), forwardFormula));
    assert interpolants.isPresent();
    assert interpolants.orElseThrow().size() == 2;
    BooleanFormula interpolant = interpolants.orElseThrow().get(1);

    return interpolant;
  }
  private int findIndexOfUnsatisfiableLocalCheck(DualInterpolationSequence pDualSequence,
                                                 PartitionedFormulas pPartitionedFormulas)
      throws SolverException, InterruptedException {
    List<BooleanFormula> FRS = pDualSequence.getForwardReachVector();
    List<BooleanFormula> BRS = pDualSequence.getBackwardReachVector();
    List<BooleanFormula> transitionFormulae = pPartitionedFormulas.getLoopFormulas();
    int n = FRS.size();

    for (int i = FRS.size() - 1; i >= 0; i--) {
      boolean isNotReachableWithOneTransition;
      stats.assertionsCheck.start();

      try {
        isNotReachableWithOneTransition =
            solver.isUnsat(bfmgr.and(FRS.get(i), BRS.get(n-i), transitionFormulae.get(i)));
      } finally {
        stats.assertionsCheck.stop();
      }
      if (isNotReachableWithOneTransition) {
        return i;
      }
    }
    return -1;
  }

  private void fallBackToBMC(final String pReason) {
    logger.log(
        Level.WARNING, "Interpolation disabled because of " + pReason + ", falling back to BMC");
    isDAREnabled = false;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    super.collectStatistics(pStatsCollection);
    pStatsCollection.add(
        new Statistics() {
          @Override
          public void printStatistics(
              PrintStream out, Result result, UnmodifiableReachedSet reached) {
            itpMgr.printStatistics(writingStatisticsTo(out));
          }

          @Override
          public @Nullable String getName() {
            return "Interpolating SMT solver";
          }
        });
  }

  @Override
  protected CandidateGenerator getCandidateInvariants() {
    throw new AssertionError(
        "Class "
            + getClass().getSimpleName()
            + " does not support this function. It should not be called.");
  }
}