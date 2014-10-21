/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.util.predicates.interpolation;

import static com.google.common.base.Preconditions.*;
import static com.google.common.collect.FluentIterable.from;
import static org.sosy_lab.cpachecker.util.statistics.StatisticsUtils.div;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import org.sosy_lab.common.Appender;
import org.sosy_lab.common.Classes.UnexpectedCheckedException;
import org.sosy_lab.common.Pair;
import org.sosy_lab.common.Triple;
import org.sosy_lab.common.concurrency.Threads;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.configuration.TimeSpanOption;
import org.sosy_lab.common.io.Path;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.time.TimeSpan;
import org.sosy_lab.common.time.Timer;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.ShutdownNotifier;
import org.sosy_lab.cpachecker.core.counterexample.Model;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.exceptions.RefinementFailedException;
import org.sosy_lab.cpachecker.exceptions.RefinementFailedException.Reason;
import org.sosy_lab.cpachecker.exceptions.SolverException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.predicates.FormulaManagerFactory;
import org.sosy_lab.cpachecker.util.predicates.Solver;
import org.sosy_lab.cpachecker.util.predicates.interfaces.BooleanFormula;
import org.sosy_lab.cpachecker.util.predicates.interfaces.InterpolatingProverEnvironment;
import org.sosy_lab.cpachecker.util.predicates.interfaces.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.interfaces.ProverEnvironment;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.BooleanFormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;


@Options(prefix="cpa.predicate.refinement")
public final class InterpolationManager {

  private final Timer cexAnalysisTimer = new Timer();
  private final Timer satCheckTimer = new Timer();
  private final Timer getInterpolantTimer = new Timer();
  private final Timer cexAnalysisGetUsefulBlocksTimer = new Timer();
  private final Timer interpolantVerificationTimer = new Timer();
  private int reusedFormulasOnSolverStack = 0;

  public void printStatistics(PrintStream out, Result result, ReachedSet reached) {
    out.println("  Counterexample analysis:            " + cexAnalysisTimer + " (Max: " + cexAnalysisTimer.getMaxTime().formatAs(TimeUnit.SECONDS) + ", Calls: " + cexAnalysisTimer.getNumberOfIntervals() + ")");
    if (cexAnalysisGetUsefulBlocksTimer.getNumberOfIntervals() > 0) {
      out.println("    Cex.focusing:                     " + cexAnalysisGetUsefulBlocksTimer + " (Max: " + cexAnalysisGetUsefulBlocksTimer.getMaxTime().formatAs(TimeUnit.SECONDS) + ")");
    }
    out.println("    Refinement sat check:             " + satCheckTimer);
    if (reuseInterpolationEnvironment && satCheckTimer.getNumberOfIntervals() > 0) {
      out.println("    Reused formulas on solver stack:  " + reusedFormulasOnSolverStack + " (Avg: " + div(reusedFormulasOnSolverStack, satCheckTimer.getNumberOfIntervals()) + ")");
    }
    out.println("    Interpolant computation:          " + getInterpolantTimer);
    if (interpolantVerificationTimer.getNumberOfIntervals() > 0) {
      out.println("    Interpolant verification:         " + interpolantVerificationTimer);
    }
  }


  private final LogManager logger;
  private final ShutdownNotifier shutdownNotifier;
  private final FormulaManagerView fmgr;
  private final BooleanFormulaManagerView bfmgr;
  private final PathFormulaManager pmgr;
  private final Solver solver;

  private final FormulaManagerFactory factory;
  private final Interpolator<?> interpolator;

  @Option(description="apply deletion-filter to the abstract counterexample, to get "
    + "a minimal set of blocks, before applying interpolation-based refinement")
  private boolean getUsefulBlocks = false;

  @Option(name="incrementalCexTraceCheck",
      description="use incremental search in counterexample analysis, "
        + "to find the minimal infeasible prefix")
  private boolean incrementalCheck = false;

  @Option(name="cexTraceCheckDirection",
      description="Direction for doing counterexample analysis: from start of trace, from end of trace, or alternatingly from start and end of the trace towards the middle")
  private CexTraceAnalysisDirection direction = CexTraceAnalysisDirection.FORWARDS;
  private static enum CexTraceAnalysisDirection {
    FORWARDS,
    BACKWARDS,
    ZIGZAG,
    ;
  }

  @Option(name="strategy",
      description="Strategy how to interact with the intepolating prover. " +
          "The analysis must support the strategy, otherwise the result will be useless!" +
          "\n- CPACHECKER_SEQ: We simply return each interpolant for i={0..n-1} for the partitions A=[0 .. i] and B=[i+1 .. n]. " +
          "The result is similar to INDUCTIVE_SEQ, but we do not guarantee the 'inductiveness', i.e. the solver has to generate nice interpolants. " +
          "\n- INDUCTIVE_SEQ: Generate an inductive sequence of interpolants the partitions [1,...n]. " +
          "\n- CPACHECKER_WELLSCOPED: We return each interpolant for i={0..n-1} for the partitions " +
          "A=[lastFunctionEntryIndex .. i] and B=[0 .. lastFunctionEntryIndex-1 , i+1 .. n]." +
          "\n- NESTED: use callstack and previous interpolants for next interpolants (see 'Nested Interpolants')," +
          "\n- NESTED2: use callstack and previous interpolants for next interpolants (see 'Nested Interpolants'," +
          "with modification: pathformulas for call and return are True)." +
          "\n- TREE: use the tree-interpolation-feature of a solver to get interpolants similar to NESTED3")
  private InterpolationStrategy strategy = InterpolationStrategy.CPACHECKER_SEQ;
  private static enum InterpolationStrategy {CPACHECKER_SEQ, INDUCTIVE_SEQ, CPACHECKER_WELLSCOPED, SEQUENCE, NESTED, NESTED2, NESTED3, TREE};

  @Option(description="dump all interpolation problems")
  private boolean dumpInterpolationProblems = false;

  @Option(description="verify if the interpolants fulfill the interpolant properties")
  private boolean verifyInterpolants = false;

  @Option(name="timelimit",
      description="time limit for refinement (use milliseconds or specify a unit; 0 for infinite)")
  @TimeSpanOption(codeUnit=TimeUnit.MILLISECONDS,
      defaultUserUnit=TimeUnit.MILLISECONDS,
      min=0)
  private TimeSpan itpTimeLimit = TimeSpan.ofMillis(0);

  @Option(description="skip refinement if input formula is larger than "
    + "this amount of bytes (ignored if 0)")
  private int maxRefinementSize = 0;

  @Option(description="Use a single SMT solver environment for several interpolation queries")
  private boolean reuseInterpolationEnvironment = false;

  private final ExecutorService executor;


  public InterpolationManager(
      FormulaManagerView pFmgr,
      PathFormulaManager pPmgr,
      Solver pSolver,
      FormulaManagerFactory pFmgrFactory,
      Configuration config,
      ShutdownNotifier pShutdownNotifier,
      LogManager pLogger) throws InvalidConfigurationException {
    config.inject(this, InterpolationManager.class);

    logger = pLogger;
    shutdownNotifier = pShutdownNotifier;
    fmgr = pFmgr;
    bfmgr = fmgr.getBooleanFormulaManager();
    pmgr = pPmgr;
    solver = pSolver;
    factory = pFmgrFactory;

    if (itpTimeLimit.isEmpty()) {
      executor = null;
    } else {
      // important to use daemon threads here, because we never have the chance to stop the executor
      executor = Executors.newSingleThreadExecutor(Threads.threadFactoryBuilder().setDaemon(true).build());
    }

    if (reuseInterpolationEnvironment) {
      interpolator = new Interpolator<>();
    } else {
      interpolator = null;
    }
  }

  public Appender dumpCounterexample(CounterexampleTraceInfo cex) {
    return fmgr.dumpFormula(bfmgr.and(cex.getCounterExampleFormulas()));
  }

  /**
   * Counterexample analysis.
   * This method is just an helper to delegate the actual work
   * This is used to detect timeouts for interpolation
   *
   * @param pFormulas the formulas for the path
   * @param pAbstractionStates the abstraction states between the formulas and the last state of the path.
   *                           The first state (root) of the path is missing, because it is always TRUE.
   *                           (can be empty, if well-scoped interpolation is disabled or not required)
   * @param elementsOnPath the ARGElements on the path (may be empty if no branching information is required)
   * @throws CPAException
   * @throws InterruptedException
   */
  public CounterexampleTraceInfo buildCounterexampleTrace(
      final List<BooleanFormula> pFormulas,
      final List<AbstractState> pAbstractionStates,
      final Set<ARGState> elementsOnPath,
      final boolean computeInterpolants) throws CPAException, InterruptedException {

    assert pAbstractionStates.isEmpty() || pFormulas.size() == pAbstractionStates.size();

    // if we don't want to limit the time given to the solver
    if (itpTimeLimit.isEmpty()) {
      return buildCounterexampleTrace0(pFormulas, pAbstractionStates, elementsOnPath, computeInterpolants);
    }

    assert executor != null;

    Callable<CounterexampleTraceInfo> tc = new Callable<CounterexampleTraceInfo>() {
      @Override
      public CounterexampleTraceInfo call() throws CPAException, InterruptedException {
        return buildCounterexampleTrace0(pFormulas, pAbstractionStates, elementsOnPath, computeInterpolants);
      }
    };

    Future<CounterexampleTraceInfo> future = executor.submit(tc);

    try {
      // here we get the result of the post computation but there is a time limit
      // given to complete the task specified by timeLimit
      return future.get(itpTimeLimit.asNanos(), TimeUnit.NANOSECONDS);

    } catch (TimeoutException e) {
      logger.log(Level.SEVERE, "SMT-solver timed out during interpolation process");
      throw new RefinementFailedException(Reason.TIMEOUT, null);

    } catch (ExecutionException e) {
      Throwable t = e.getCause();
      Throwables.propagateIfPossible(t, CPAException.class, InterruptedException.class);

      throw new UnexpectedCheckedException("interpolation", t);
    }
  }

  public CounterexampleTraceInfo buildCounterexampleTrace(
      final List<BooleanFormula> pFormulas,
      final List<AbstractState> pAbstractionStates,
      final Set<ARGState> elementsOnPath) throws CPAException, InterruptedException {
    return buildCounterexampleTrace(pFormulas, pAbstractionStates, elementsOnPath, true);
  }

  public CounterexampleTraceInfo buildCounterexampleTrace(
          final List<BooleanFormula> pFormulas) throws CPAException, InterruptedException {
    return buildCounterexampleTrace(
            pFormulas, Collections.<AbstractState>emptyList(), Collections.<ARGState>emptySet(), true);
  }

  private CounterexampleTraceInfo buildCounterexampleTrace0(
      final List<BooleanFormula> pFormulas,
      final List<AbstractState> pAbstractionStates,
      final Set<ARGState> elementsOnPath,
      final boolean computeInterpolants) throws CPAException, InterruptedException {

    logger.log(Level.FINEST, "Building counterexample trace");
    cexAnalysisTimer.start();
    try {

      // Final adjustments to the list of formulas
      List<BooleanFormula> f = new ArrayList<>(pFormulas); // copy because we will change the list

      if (fmgr.useBitwiseAxioms()) {
        addBitwiseAxioms(f);
      }

      f = Collections.unmodifiableList(f);
      logger.log(Level.ALL, "Counterexample trace formulas:", Joiner.on("\n").join(f));

      // now f is the DAG formula which is satisfiable iff there is a
      // concrete counterexample


      // Check if refinement problem is not too big
      if (maxRefinementSize > 0) {
        int size = fmgr.dumpFormula(bfmgr.and(f)).toString().length();
        if (size > maxRefinementSize) {
          logger.log(Level.FINEST, "Skipping refinement because input formula is", size, "bytes large.");
          throw new RefinementFailedException(Reason.TooMuchUnrolling, null);
        }
      }

      final Interpolator<?> currentInterpolator;
      if (reuseInterpolationEnvironment) {
        currentInterpolator = checkNotNull(interpolator);
      } else {
        currentInterpolator = new Interpolator<>();
      }

      try {
        return currentInterpolator.buildCounterexampleTrace(f, pAbstractionStates, elementsOnPath, computeInterpolants);
      } finally {
        if (!reuseInterpolationEnvironment) {
          currentInterpolator.close();
        }
      }

    } finally {
      cexAnalysisTimer.stop();
    }
  }

  /**
   * Add axioms about bitwise operations to a list of formulas, if such operations
   * are used. This is probably not that helpful currently, we would have to the
   * tell the solver that these are axioms.
   *
   * The axioms are added to the last part of the list of formulas.
   *
   * @param f The list of formulas to scan for bitwise operations.
   */
  private void addBitwiseAxioms(List<BooleanFormula> f) {
    BooleanFormula bitwiseAxioms = bfmgr.makeBoolean(true);

    for (BooleanFormula fm : f) {
      BooleanFormula a = fmgr.getBitwiseAxioms(fm);
      if (!bfmgr.isTrue(a)) {
        bitwiseAxioms =  fmgr.getBooleanFormulaManager().and(bitwiseAxioms, a);
      }
    }

    if (!bfmgr.isTrue(bitwiseAxioms)) {
      logger.log(Level.ALL, "DEBUG_3", "ADDING BITWISE AXIOMS TO THE",
          "LAST GROUP: ", bitwiseAxioms);
      int lastIndex = f.size()-1;
      f.set(lastIndex, bfmgr.and(f.get(lastIndex), bitwiseAxioms));
    }
  }

  /**
   * Try to find out which formulas out of a list of formulas are relevant for
   * making the conjunction unsatisfiable.
   * This method honors the {@link #direction} configuration option.
   *
   * @param f The list of formulas to check.
   * @return A sublist of f that contains the useful formulas.
   */
  private List<BooleanFormula> getUsefulBlocks(List<BooleanFormula> f) throws InterruptedException {

    cexAnalysisGetUsefulBlocksTimer.start();

    // try to find a minimal-unsatisfiable-core of the trace (as Blast does)

    try (ProverEnvironment thmProver = solver.newProverEnvironment()) {

    logger.log(Level.ALL, "DEBUG_1", "Calling getUsefulBlocks on path",
            "of length:", f.size());

    final BooleanFormula[] needed = new BooleanFormula[f.size()];
    for (int i = 0; i < needed.length; ++i) {
      needed[i] =  bfmgr.makeBoolean(true);
    }
    final boolean backwards = direction == CexTraceAnalysisDirection.BACKWARDS;
    final int start = backwards ? f.size()-1 : 0;
    final int increment = backwards ? -1 : 1;
    int toPop = 0;

    while (true) {
      boolean consistent = true;
      // 1. assert all the needed constraints
      for (int i = 0; i < needed.length; ++i) {
        if (!bfmgr.isTrue(needed[i])) {
          thmProver.push(needed[i]);
          ++toPop;
        }
      }
      // 2. if needed is inconsistent, then return it
      if (thmProver.isUnsat()) {
        f = Arrays.asList(needed);
        break;
      }
      // 3. otherwise, assert one block at a time, until we get an
      // inconsistency
      if (direction == CexTraceAnalysisDirection.ZIGZAG) {
        int s = 0;
        int e = f.size()-1;
        boolean fromStart = false;
        while (true) {
          int i = fromStart ? s++ : e--;
          fromStart = !fromStart;

          BooleanFormula t = f.get(i);
          thmProver.push(t);
          ++toPop;
          if (thmProver.isUnsat()) {
            // add this block to the needed ones, and repeat
            needed[i] = t;
            logger.log(Level.ALL, "DEBUG_1",
                "Found needed block: ", i, ", term: ", t);
            // pop all
            while (toPop > 0) {
              --toPop;
              thmProver.pop();
            }
            // and go to the next iteration of the while loop
            consistent = false;
            break;
          }

          if (e < s) {
            break;
          }
        }
      } else {
        for (int i = start;
             backwards ? i >= 0 : i < f.size();
             i += increment) {
          BooleanFormula t = f.get(i);
          thmProver.push(t);
          ++toPop;
          if (thmProver.isUnsat()) {
            // add this block to the needed ones, and repeat
            needed[i] = t;
            logger.log(Level.ALL, "DEBUG_1",
                "Found needed block: ", i, ", term: ", t);
            // pop all
            while (toPop > 0) {
              --toPop;
              thmProver.pop();
            }
            // and go to the next iteration of the while loop
            consistent = false;
            break;
          }
        }
      }
      if (consistent) {
        // if we get here, the trace is consistent:
        // this is a real counterexample!
        break;
      }
    }

    while (toPop > 0) {
      --toPop;
      thmProver.pop();
    }

    }

    logger.log(Level.ALL, "DEBUG_1", "Done getUsefulBlocks");

    cexAnalysisGetUsefulBlocksTimer.stop();

    return f;
  }

  /**
   * Put the list of formulas into the order in which they should be given to
   * the solver, as defined by the {@link #direction} configuration option.
   * @param traceFormulas The list of formulas to check.
   * @return The same list of formulas in different order,
   *         and each formula has its position in the original list as third element of the pair.
   */
  private List<Triple<BooleanFormula, AbstractState, Integer>> orderFormulas(
          final List<BooleanFormula> traceFormulas, final List<AbstractState> pAbstractionStates) {

    // In this list are all formulas together with their position in the original list
    ImmutableList.Builder<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas = ImmutableList.builder();

    if (direction == CexTraceAnalysisDirection.ZIGZAG) {
      int e = traceFormulas.size()-1;
      int s = 0;
      boolean fromStart = false;
      while (s <= e) {
        int i = fromStart ? s++ : e--;
        fromStart = !fromStart;

        orderedFormulas.add(Triple.of(traceFormulas.get(i), pAbstractionStates.get(i), i));
      }

    } else {
      final boolean backwards = direction == CexTraceAnalysisDirection.BACKWARDS;
      final int increment = backwards ? -1 : 1;

      for (int i = backwards ? traceFormulas.size()-1 : 0;
           backwards ? i >= 0 : i < traceFormulas.size();
           i += increment) {

        orderedFormulas.add(Triple.of(traceFormulas.get(i), pAbstractionStates.get(i), i));
      }
    }

    ImmutableList<Triple<BooleanFormula, AbstractState, Integer>> result = orderedFormulas.build();
    assert traceFormulas.size() == result.size();
    assert ImmutableMultiset.copyOf(from(result).transform(Triple.getProjectionToFirst()))
            .equals(ImmutableMultiset.copyOf(traceFormulas))
            : "Ordered list does not contain the same formulas with the same count";
    return result;
  }

  /**
   * Get the interpolants from the solver after the formulas have been proved
   * to be unsatisfiable.
   *
   * @param pItpProver The solver.
   * @param itpGroupsIds The references to the interpolation groups, sorting depends on the solver-stack.
   * @param orderedFormulas list of formulas with their (nullable) successor-state and the index in the "correct" order.
   * @return A list of all the interpolants.
   */
  private <T> List<BooleanFormula> getInterpolants(
      final Interpolator<T> interpolator, List<T> itpGroupsIds,
      final List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas) throws InterruptedException {

    assert itpGroupsIds.size() == orderedFormulas.size();

    List<BooleanFormula> interpolants = Lists.newArrayListWithExpectedSize(itpGroupsIds.size()-1);

    // The counterexample is spurious. Get the interpolants.

    switch (strategy) {
      case CPACHECKER_SEQ: {
        for (int end_of_A = 0; end_of_A < itpGroupsIds.size() - 1; end_of_A++) {
          // last iteration is left out because B would be empty
          final int start_of_A = 0;
          interpolants.add(getInterpolantFromSublist(interpolator.itpProver, itpGroupsIds, start_of_A, end_of_A, 0));
        }
        break;
      }

      case INDUCTIVE_SEQ: {
        // wrap into singleton to match interface-type
        final List<Set<T>> itpGroups = new ArrayList<>();
        for (T f : itpGroupsIds) {
          itpGroups.add(Collections.singleton(f));
        }
        interpolants = interpolator.itpProver.getSeqInterpolants(itpGroups);
      }

      case CPACHECKER_WELLSCOPED: { // TODO not fully working and not used
        final Deque<Pair<Integer, CFANode>> callstack = new ArrayDeque<>();
        for (int end_of_A = 0; end_of_A < itpGroupsIds.size() - 1; end_of_A++) {
          // last iteration is left out because B would be empty
          final int start_of_A = getWellScopedStartOfA(orderedFormulas, callstack, end_of_A);
          interpolants.add(getInterpolantFromSublist(interpolator.itpProver, itpGroupsIds, start_of_A, end_of_A, callstack.size()));
        }
        break;
      }

      case NESTED: {

        BooleanFormula lastItp = bfmgr.makeBoolean(true); // PSI_0 = True
        final Deque<Triple<BooleanFormula,BooleanFormula,CFANode>> callstack = new ArrayDeque<>();

        for (int positionOfA = 0; positionOfA < orderedFormulas.size() - 1; positionOfA++) {

          // use a new prover, because we use several distinct queries
          final InterpolatingProverEnvironment<T> itpProver = interpolator.newEnvironment();
          lastItp = getNestedInterpolant(orderedFormulas, interpolants, callstack, itpProver, positionOfA, lastItp);
          itpProver.close();
        }

        break;
      }

      case NESTED2: {

        BooleanFormula lastItp = bfmgr.makeBoolean(true); // PSI_0 = True
        final Deque<Triple<BooleanFormula,BooleanFormula,CFANode>> callstack = new ArrayDeque<>();

        for (int positionOfA = 0; positionOfA < orderedFormulas.size() - 1; positionOfA++) {

          // use a new prover, because we use several distinct queries
          final InterpolatingProverEnvironment<T> itpProver = interpolator.newEnvironment();
          lastItp = getNestedInterpolant2(orderedFormulas, interpolants, callstack,  itpProver, positionOfA, lastItp);
          itpProver.close();
        }
        break;
      }

      case NESTED3: {

        BooleanFormula lastItp = bfmgr.makeBoolean(true); // PSI_0 = True
        final Deque<Triple<BooleanFormula,BooleanFormula,CFANode>> callstack = new ArrayDeque<>();

        for (int positionOfA = 0; positionOfA < orderedFormulas.size() - 1; positionOfA++) {

          // use a new prover, because we use several distinct queries
          lastItp = getNestedInterpolant3(orderedFormulas, interpolants, callstack, interpolator, positionOfA, lastItp);
        }
        break;
      }

      case TREE: {

        final Pair<List<T>, List<Integer>> p = buildTreeStructure(itpGroupsIds, orderedFormulas);

        final List<BooleanFormula> itps = interpolator.itpProver.getTreeInterpolants(
            Lists.transform(p.getFirst(), new Function<T, Set<T>>() {
              @Override
              public Set<T> apply(T f) {
                return Collections.singleton(f);
              }
            }),
            Ints.toArray(p.getSecond()));

        logger.log(Level.ALL, "received interpolants of tree :", itps);
        assert p.getFirst().size() - 1 == itps.size() : "expecting N-1 interpolants for N formulas";

        interpolants = flattenTreeItps(orderedFormulas, itps);
        break;
      }

      default:
        throw new AssertionError("should not happen");
    }

    assert orderedFormulas.size() - 1 == interpolants.size() : "we should return N-1 interpolants for N formulas.";
    return interpolants;
  }

  /**
   * Build a tree of formulas according to controlflow (function calls and returns).
   *
   * @param itpGroupsIds formulas from the solver-stack, sorted according controlflow
   * @param orderedFormulas formulas and abstract states, sorted according to position on the solver-stack.
   *                        we assume DIRECTION.FORWARDS as order, such that itpGroups and orderedFormulas are sorted equal.
   *
   * @return Pair (pFormulas := tree-elements, pStartOfSubTree := tree-structure)
   */
  private <T> Pair<List<T>, List<Integer>> buildTreeStructure(
      final List<T> itpGroupsIds,
      final List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas) {

    final List<T> formulas = new ArrayList<>();
    final List<Integer> startOfSubTree = new ArrayList<>();

    final Deque<Triple<T, Integer, BooleanFormula>> stack = new ArrayDeque<>();
    final List<BooleanFormula> formulas2 = new ArrayList<>(); // only for logging

    stack.add(Triple.of(itpGroupsIds.get(0), 0, orderedFormulas.get(0).getFirst())); // every complete tree start at left-most node
    for (int positionOfA = 0; positionOfA < orderedFormulas.size(); positionOfA++) {
      // first element is handled before

      // If we have entered or exited a function, update the stack of entry points
      final AbstractState abstractionState = checkNotNull(orderedFormulas.get(positionOfA).getSecond());
      final CFANode node = AbstractStates.extractLocation(abstractionState);
      final T formula = itpGroupsIds.get(positionOfA);
      final BooleanFormula formula2 = orderedFormulas.get(positionOfA).getFirst();

      if (node instanceof FunctionEntryNode && callHasReturn(orderedFormulas, positionOfA)) {
        // start new left subtree, i.e. next formula is left leaf of a subtree.
        // current formula will be used as merge-formula (common root of new subtree and previous formulas)
        stack.addLast(Triple.of(formula, formulas.size(), formula2));

      } else if (/*!stack.isEmpty() &&*/ node instanceof FunctionExitNode) {

        // first add the last inner formula
        startOfSubTree.add(stack.getLast().getSecond());
        formulas.add(formula);
        formulas2.add(formula2);

        // then add the common root (merge-formula)
        final Triple<T, Integer, BooleanFormula> commonRoot = stack.removeLast();
        startOfSubTree.add(stack.getLast().getSecond());
        formulas.add(commonRoot.getFirst());
        formulas2.add(commonRoot.getThird());

        assert commonRoot.getSecond() >= stack.getLast().getSecond() : "adding a complete subtree can only be done on the right side";

      } else {
        startOfSubTree.add(stack.getLast().getSecond());
        formulas.add(formula);
        formulas2.add(formula2);
      }

      assert formulas.size() == startOfSubTree.size() : "invalid number of tree elements: " + startOfSubTree;
    }

    logger.log(Level.ALL, "formulas of tree are:", formulas2);
    logger.log(Level.ALL, "subtree-structure is:", startOfSubTree);
    assert formulas.size() == orderedFormulas.size() : "invalid number of tree elements: " + formulas.size() + " vs " + orderedFormulas.size();

    return Pair.of(formulas, startOfSubTree);
  }

  /**
   * The default Predicate Analysis can only handle a flat list of interpolants.
   * Thus we convert the tree-structure back into a linear chain of interpolants.
   * The analysis must handle special cases on its own, i.e. use BAM with function-rebuilding.
   *
   * For function-entries we use TRUE,
   * for function-returns both function-summary and function-execution (merged into one formula).
   *
   * @param orderedFormulas contains the input formulas and abstract states
   * @param itps tree-interpolants
   * @return interpolants linear chain of interpolants, created from the tree-interpolants
   */
  private List<BooleanFormula> flattenTreeItps(
      final List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas,
      final List<BooleanFormula> itps) {
    final List<BooleanFormula> interpolants = new ArrayList<>();
    final Iterator<BooleanFormula> iter = itps.iterator();
    final Deque<BooleanFormula> stack2 = new ArrayDeque<>();
    for (int positionOfA = 0; positionOfA < orderedFormulas.size() - 1; positionOfA++) {
      // last interpolant would be False.

      // If we have entered or exited a function, update the stack of entry points
      final AbstractState abstractionState = checkNotNull(orderedFormulas.get(positionOfA).getSecond());
      final CFANode node = AbstractStates.extractLocation(abstractionState);

      if (node instanceof FunctionEntryNode && callHasReturn(orderedFormulas, positionOfA)) {
        interpolants.add(bfmgr.makeBoolean(true));
        stack2.addLast(interpolants.get(interpolants.size() - 1));

      } else if (/*!stack.isEmpty() &&*/ node instanceof FunctionExitNode) {
        // add the last inner formula and the common root (merge-formula)
        final BooleanFormula functionSummary = iter.next();
        final BooleanFormula functionExecution = iter.next();
        interpolants.add(rebuildInterpolant(functionSummary, functionExecution));

      } else {
        interpolants.add(iter.next());
      }
    }

    assert !iter.hasNext() : "remaining interpolants: " + Lists.newArrayList(iter);

    return interpolants;
  }

  private <T> BooleanFormula getNestedInterpolant(
          final List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas,
          final List<BooleanFormula> interpolants,
          final Deque<Triple<BooleanFormula, BooleanFormula, CFANode>> callstack,
          final InterpolatingProverEnvironment<T> itpProver,
          int positionOfA, BooleanFormula lastItp) throws InterruptedException {

    final List<T> A = buildFormulas(orderedFormulas, lastItp, callstack, positionOfA, itpProver);

    // update prover with new formulas.
    // this is the expensive step, that is distinct from other strategies.
    // TODO improve! example: reverse ordering of formulas for re-usage of the solver-stack
    boolean unsat = itpProver.isUnsat();
    assert unsat : "formulas were unsat before, they have to be unsat now.";

    // get interpolant of A and B, for B we use the complementary set of A
    logger.log(Level.ALL, "Looking for interpolant for formulas at position", positionOfA, "(depth", callstack.size(), ")");
    final BooleanFormula itp = itpProver.getInterpolant(A);
    logger.log(Level.ALL, "Received interpolant", itp);

    interpolants.add(itp);
    return itp;
  }

  /** build groups A and B from formulas and update the solver-stack. */
  private <T> List<T> buildFormulas(final List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas,
                                    BooleanFormula lastItp, final Deque<Triple<BooleanFormula, BooleanFormula, CFANode>> callstack,
                                    final int positionOfA, final InterpolatingProverEnvironment<T> itpProver) {

    final List<T> A = new ArrayList<>();
    final List<T> B = new ArrayList<>();

    // If we have entered or exited a function, update the stack of entry points
    final AbstractState abstractionState = checkNotNull(orderedFormulas.get(positionOfA).getSecond());
    final CFANode node = AbstractStates.extractLocation(abstractionState);
    if (node.getNumEnteringEdges() == 1
            && node.getEnteringEdge(0).getPredecessor() instanceof FunctionEntryNode) {
      if (positionOfA > 0 && !callHasReturn(orderedFormulas, positionOfA-1)) {
        // case 3 from paper
        A.add(itpProver.push(lastItp));
        A.add(itpProver.push(orderedFormulas.get(positionOfA).getFirst()));
        logger.log(Level.ALL, "\n3. A =", lastItp, "+", orderedFormulas.get(positionOfA).getFirst());

      } else {
        // case 2 from paper
        callstack.addLast(Triple.of(lastItp, orderedFormulas.get(positionOfA).getFirst(), node));
        A.add(itpProver.push(bfmgr.makeBoolean(true)));
        logger.log(Level.ALL, "\n2. A = TRUE");
      }

    } else {
      // case 1 and 4 from paper, internal position OR function-return
      A.add(itpProver.push(lastItp));
      A.add(itpProver.push(orderedFormulas.get(positionOfA).getFirst()));
      logger.log(Level.ALL, "\n14. A =", lastItp, "+", orderedFormulas.get(positionOfA).getFirst());
    }

    // case 4, we are returning from a function,
    if (!callstack.isEmpty()) {
      final CFANode lastEntryNode = callstack.getLast().getThird();
      if (node instanceof FunctionExitNode
        //&& ((FunctionExitNode) node).getEntryNode() == lastEntryNode
        //|| (node.getEnteringSummaryEdge() != null
        //     && node.getEnteringSummaryEdge().getPredecessor().getLeavingEdge(0).getSuccessor() == lastEntryNode)
              ) {
        A.add(itpProver.push(callstack.getLast().getFirst()));
        A.add(itpProver.push(callstack.getLast().getSecond()));
        logger.log(Level.ALL, "\n2. A +=", callstack.getLast());
        callstack.removeLast();
      }
    }

    // add all remaining PHI_j
    for (Triple<BooleanFormula, AbstractState, Integer> t : Iterables.skip(orderedFormulas, positionOfA + 1)) {
      B.add(itpProver.push(t.getFirst()));
      //logger.log(Level.ALL, "\n1. B +=", t.getFirst()); // disabled, too many lines
    }

    // add all previous function calls
    for (Triple<BooleanFormula,BooleanFormula, CFANode> t : callstack) {
      B.add(itpProver.push(t.getFirst())); // add PSI_k
      B.add(itpProver.push(t.getSecond())); // ... and PHI_k
      logger.log(Level.ALL, "\n2. B +=", t.getFirst());
    }

    return A;
  }

  /** This function implements the paper "Nested Interpolants" with a small modification:
   * instead of call- and return-edges, we use dummy-edges with simple pathformula "true".
   * Actually the implementation does not use "true", but omits it completely. */
  private <T> BooleanFormula getNestedInterpolant2(
          final List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas,
          final List<BooleanFormula> interpolants,
          final Deque<Triple<BooleanFormula, BooleanFormula, CFANode>> callstack,
          final InterpolatingProverEnvironment<T> itpProver,
          int positionOfA, BooleanFormula lastItp) throws InterruptedException {
    final List<T> A = new ArrayList<>();
    final List<T> B = new ArrayList<>();

    // If we have entered or exited a function, update the stack of entry points
    final AbstractState abstractionState = checkNotNull(orderedFormulas.get(positionOfA).getSecond());
    final CFANode node = AbstractStates.extractLocation(abstractionState);

    if (node instanceof FunctionEntryNode && callHasReturn(orderedFormulas, positionOfA)) {
      // && (positionOfA > 0)) {
      // case 2 from paper
      final BooleanFormula call = orderedFormulas.get(positionOfA).getFirst();
      callstack.addLast(Triple.of(lastItp, call, node));
      logger.log(Level.ALL, "CALL:", call, node);
      final BooleanFormula itp = bfmgr.makeBoolean(true);
      interpolants.add(itp);
      return itp; // PSIminus = True --> PSI = True, for the 3rd rule ITP is True
    }

    A.add(itpProver.push(lastItp));
    A.add(itpProver.push(orderedFormulas.get(positionOfA).getFirst()));
    logger.log(Level.ALL, "\n1. A =", lastItp, "+", orderedFormulas.get(positionOfA).getFirst());

    // add all remaining PHI_j
    for (Triple<BooleanFormula, AbstractState, Integer> t : Iterables.skip(orderedFormulas, positionOfA + 1)) {
      B.add(itpProver.push(t.getFirst()));
      //logger.log(Level.ALL, "\n1. B +=", t.getFirst()); // disabled, too many lines
    }

    // add all previous function calls
    for (Triple<BooleanFormula,BooleanFormula, CFANode> t : callstack) {
      B.add(itpProver.push(t.getFirst())); // add PSI_k
      B.add(itpProver.push(t.getSecond())); // ... and PHI_k
      logger.log(Level.ALL, "\n2. B +=", t.getFirst());
    }

    // update prover with new formulas.
    // this is the expensive step, that is distinct from other strategies.
    // TODO improve! example: reverse ordering of formulas for re-usage of the solver-stack
    boolean unsat = itpProver.isUnsat();
    assert unsat : "formulas were unsat before, they have to be unsat now.";

    // get interpolant of A and B, for B we use the complementary set of A
    logger.log(Level.ALL, "Looking for interpolant for formulas at position", positionOfA, "(depth", callstack.size(), ")");
    final BooleanFormula itp = itpProver.getInterpolant(A);
    logger.log(Level.ALL, "Received interpolant", itp);

    if (!callstack.isEmpty() && node instanceof FunctionExitNode) {
      // case 4, we are returning from a function, rule 4
      Triple<BooleanFormula, BooleanFormula, CFANode> scopingItp = callstack.removeLast();
      A.add(itpProver.push(scopingItp.getFirst()));
      A.add(itpProver.push(scopingItp.getSecond()));
      logger.log(Level.ALL, "\n4. A +=", scopingItp);
      // we want many atoms here, the formula itself is irrelevant
      final BooleanFormula rebuildItp = bfmgr.and(ImmutableList.of(
              itp, scopingItp.getFirst(), scopingItp.getSecond(), lastItp));
      interpolants.add(rebuildItp);
      return rebuildItp;

    } else {
      interpolants.add(itp);
      return itp;
    }
  }

  /** This function implements the paper "Nested Interpolants" with a small modification:
   * instead of a return-edge, we use dummy-edges with simple pathformula "true".
   * Actually the implementation does not use "true", but omits it completely and
   * returns the conjunction of the two interpolants (before and after the (non-existing) dummy edge). */
  private <T> BooleanFormula getNestedInterpolant3(
          final List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas,
          final List<BooleanFormula> interpolants,
          final Deque<Triple<BooleanFormula, BooleanFormula, CFANode>> callstack,
          final Interpolator<T> interpolator,
          int positionOfA, BooleanFormula lastItp) throws InterruptedException {
    try (final InterpolatingProverEnvironment<T> itpProver = interpolator.newEnvironment()) {

      final boolean log = false;

      final List<T> A = new ArrayList<>();
      final List<T> B = new ArrayList<>();

      // If we have entered or exited a function, update the stack of entry points
      final AbstractState abstractionState = checkNotNull(orderedFormulas.get(positionOfA).getSecond());
      final CFANode node = AbstractStates.extractLocation(abstractionState);

      if (node instanceof FunctionEntryNode && callHasReturn(orderedFormulas, positionOfA)) {
        // && (positionOfA > 0)) {
        // case 2 from paper
        final BooleanFormula call = orderedFormulas.get(positionOfA).getFirst();
        callstack.addLast(Triple.of(lastItp, call, node));
        if (log) logger.log(Level.ALL, "CALL:", call, node);
        final BooleanFormula itp = bfmgr.makeBoolean(true);
        interpolants.add(itp);
        return itp; // PSIminus = True --> PSI = True, for the 3rd rule ITP is True
      }

      A.add(itpProver.push(lastItp));
      A.add(itpProver.push(orderedFormulas.get(positionOfA).getFirst()));
      if (log) logger.log(Level.ALL, "\n1. A =", lastItp, "+", orderedFormulas.get(positionOfA).getFirst());

      // add all remaining PHI_j
      for (Triple<BooleanFormula, AbstractState, Integer> t : Iterables.skip(orderedFormulas, positionOfA + 1)) {
        B.add(itpProver.push(t.getFirst()));
        //logger.log(Level.ALL, "\n1. B +=", t.getFirst()); // disabled, too many lines
      }

      // add all previous function calls
      for (Triple<BooleanFormula,BooleanFormula, CFANode> t : callstack) {
        B.add(itpProver.push(t.getFirst())); // add PSI_k
        B.add(itpProver.push(t.getSecond())); // ... and PHI_k
        if (log) logger.log(Level.ALL, "\n2. B +=", t.getFirst());
      }

      // update prover with new formulas.
      // this is the expensive step, that is distinct from other strategies.
      // TODO improve! example: reverse ordering of formulas for re-usage of the solver-stack
      boolean unsat = itpProver.isUnsat();
      assert unsat : "formulas were unsat before, they have to be unsat now.";

      // get interpolant of A and B, for B we use the complementary set of A
      if (log) logger.log(Level.ALL, "Looking for interpolant for formulas at position", positionOfA, "(depth", callstack.size(), ")");
      final BooleanFormula itp = itpProver.getInterpolant(A);
      if (log) logger.log(Level.ALL, "Received interpolant", itp);

      if (!callstack.isEmpty() && node instanceof FunctionExitNode) {
        // case 4, we are returning from a function, rule 4
        Triple<BooleanFormula, BooleanFormula, CFANode> scopingItp = callstack.removeLast();

        final InterpolatingProverEnvironment<T> itpProver2 = interpolator.newEnvironment();
        final List<T> A2 = new ArrayList<>();
        final List<T> B2 = new ArrayList<>();

        A2.add(itpProver2.push(itp));
        //A2.add(itpProver2.push(orderedFormulas.get(positionOfA).getFirst()));
        if (log) logger.log(Level.ALL, "\n1. A2 =", itp, "+", orderedFormulas.get(positionOfA).getFirst());

        A2.add(itpProver2.push(scopingItp.getFirst()));
        A2.add(itpProver2.push(scopingItp.getSecond()));
        if (log) logger.log(Level.ALL, "\n4. A2 +=", scopingItp);

        // add all remaining PHI_j
        for (Triple<BooleanFormula, AbstractState, Integer> t : Iterables.skip(orderedFormulas, positionOfA + 1)) {
          B2.add(itpProver2.push(t.getFirst()));
          if (log) logger.log(Level.ALL, "\n1. B2 +=", t.getFirst()); // disabled, too many lines
        }

        // add all previous function calls
        for (Triple<BooleanFormula, BooleanFormula, CFANode> t : callstack) {
          B2.add(itpProver2.push(t.getFirst())); // add PSI_k
          B2.add(itpProver2.push(t.getSecond())); // ... and PHI_k
          if (log) logger.log(Level.ALL, "\n2. B2 +=", t.getFirst());
        }

        boolean unsat2 = itpProver2.isUnsat();
        assert unsat2 : "formulas2 were unsat before, they have to be unsat now.";

        // get interpolant of A and B, for B we use the complementary set of A
        if (log) logger.log(Level.ALL, "Looking for interpolant2 for formulas at position", positionOfA, "(depth", callstack.size(), ")");
        BooleanFormula itp2 = itpProver2.getInterpolant(A2);
        if (log) logger.log(Level.ALL, "Received interpolant2", itp2);

        itpProver2.close();

        final BooleanFormula rebuildItp = rebuildInterpolant(itp, itp2);

        if (log) logger.log(Level.ALL, "\nrebuild itp =", itp, "and", itp2, "=", rebuildItp);

        interpolants.add(rebuildItp);
        return itp2;

      } else {
        interpolants.add(itp);
        return itp;
      }
    }
  }

  /**
   * We need all atoms of both interpolants in one formula,
   * If one of the formulas is True or False, we do not get Atoms from it. Thus we remove those cases.
   */
  private BooleanFormula rebuildInterpolant(BooleanFormula functionSummary, BooleanFormula functionExecution) {
    final BooleanFormula rebuildItp;
    if (bfmgr.isTrue(functionSummary) || bfmgr.isFalse(functionSummary)) {
      rebuildItp = functionExecution;
    } else if (bfmgr.isTrue(functionExecution) || bfmgr.isFalse(functionExecution)) {
      rebuildItp = functionSummary;
    } else {
      // TODO operation OR is weak, we could also use AND.
      // There is no difference for the atoms later, because we filter out True and False here.
      rebuildItp = bfmgr.or(functionSummary, functionExecution);
    }
    return rebuildItp;
  }

  /** check, if there exists a function-exit-node to the current call-node. */
  private boolean callHasReturn(List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas, int callIndex) {
    // TODO caching as optimisation to reduce from  k*O(n)  to  O(n)+k*O(1)  ?
    final Deque<CFANode> callstack = new ArrayDeque<>();

    {
      final AbstractState abstractionState = orderedFormulas.get(callIndex).getSecond();
      final CFANode node = AbstractStates.extractLocation(abstractionState);
      assert (node instanceof FunctionEntryNode) : "call needed as input param";
      callstack.addLast(node);
    }

    // walk along path and track the callstack
    for (Triple<BooleanFormula, AbstractState, Integer> t : Iterables.skip(orderedFormulas, callIndex + 1)) {
      assert !callstack.isEmpty() : "should have returned when callstack is empty";

      final AbstractState abstractionState = checkNotNull(t.getSecond());
      final CFANode node = AbstractStates.extractLocation(abstractionState);

      if (node instanceof FunctionEntryNode) {
        callstack.addLast(node);
      }

      final CFANode lastEntryNode = callstack.getLast();
      if ((node instanceof FunctionExitNode
              && ((FunctionExitNode) node).getEntryNode() == lastEntryNode)
        //|| (node.getEnteringSummaryEdge() != null
        // && node.getEnteringSummaryEdge().getPredecessor().getLeavingEdge(0).getSuccessor() == lastEntryNode)
              ) {
        callstack.removeLast();

        // we found the function exit for the input param
        if (callstack.isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  private int getWellScopedStartOfA(List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas,
                                    Deque<Pair<Integer, CFANode>> callstack, int end_of_A) {

    checkState(direction == CexTraceAnalysisDirection.FORWARDS,
            "well-scoped predicated need to traverse the error-trace in a normal way.");
    // TODO check backward (should be working) and ZIGZAG (???)

    // TODO the following code relies on the fact that there is always an abstraction on function-calls and -returns

    if (end_of_A == 0) {
      return 0;
    }

    // If we have entered or exited a function, update the stack of entry points
    final AbstractState abstractionState = checkNotNull(orderedFormulas.get(end_of_A-1).getSecond());
    final CFANode node = AbstractStates.extractLocation(abstractionState);
    if (node instanceof FunctionEntryNode) {
      callstack.addLast(Pair.of(end_of_A, node));
    }

    // we are returning from a function,
    // TODO one node to early or to late in the CFA? or only for BAM? -> see BlockOperator for BlockEnd!
    // TODO matching node can be wrong in case of recursion of multiple calls to the same function?
    if (!callstack.isEmpty()) {
      final CFANode lastEntryNode = callstack.getLast().getSecond();
      if ((node instanceof FunctionExitNode
              && ((FunctionExitNode) node).getEntryNode() == lastEntryNode)
        //|| (node.getEnteringSummaryEdge() != null
        //       && node.getEnteringSummaryEdge().getPredecessor().getLeavingEdge(0).getSuccessor() == lastEntryNode)
              ) {
        callstack.removeLast();
      }
    }

    if (callstack.isEmpty()) {
      return 0; // we are in the main-function, so A starts at 0.
    } else {
      return callstack.getLast().getFirst();
    }
  }

  /**
   * Precondition: The solver-stack contains all formulas and is UNSAT.
   * Get the interpolant between the Sublist of formulas and the other formulas on the solver-stack.
   * Each formula is identified by its GroupId,
   * The sublist is taken from the list of GroupIds, including both start and end of A.
   */
  private <T> BooleanFormula getInterpolantFromSublist(final InterpolatingProverEnvironment<T> pItpProver,
        final List<T> itpGroupsIds, final int start_of_A, final int end_of_A, final int depth) throws InterruptedException {
    shutdownNotifier.shutdownIfNecessary();

    logger.log(Level.ALL, "Looking for interpolant for formulas from", start_of_A, "to", end_of_A, "(depth", depth, ")");

    getInterpolantTimer.start();
    final BooleanFormula itp = pItpProver.getInterpolant(itpGroupsIds.subList(start_of_A, end_of_A + 1));
    getInterpolantTimer.stop();

    logger.log(Level.ALL, "Received interpolant", itp);

    if (dumpInterpolationProblems) {
      dumpFormulaToFile("interpolant", itp, end_of_A);
    }

    return itp;
  }

  private <T> void verifyInterpolants(List<BooleanFormula> interpolants, List<BooleanFormula> formulas, InterpolatingProverEnvironment<T> prover) throws SolverException, InterruptedException {
    interpolantVerificationTimer.start();
    try {

      final int n = interpolants.size();
      assert n == (formulas.size() - 1);

      // The following three properties need to be checked:
      // (A)                          true      & f_0 => itp_0
      // (B) \forall i \in [1..n-1] : itp_{i-1} & f_i => itp_i
      // (C)                          itp_{n-1} & f_n => false

      // Check (A)
      if (!solver.implies(formulas.get(0), interpolants.get(0))) {
        throw new SolverException("First interpolant is not implied by first formula");
      }

      // Check (B).
      for (int i = 1; i <= (n-1); i++) {
        BooleanFormula conjunct = bfmgr.and(interpolants.get(i-1), formulas.get(i));

        if (!solver.implies(conjunct, interpolants.get(i))) {
          throw new SolverException("Interpolant " + interpolants.get(i) + " is not implied by previous part of the path");
        }
      }

      // Check (C).
      BooleanFormula conjunct = bfmgr.and(interpolants.get(n-1), formulas.get(n));
      if (!solver.implies(conjunct, bfmgr.makeBoolean(false))) {
        throw new SolverException("Last interpolant fails to prove infeasibility of the path");
      }


      // Furthermore, check if the interpolants contains only the allowed variables
      List<Set<String>> variablesInFormulas = Lists.newArrayListWithExpectedSize(formulas.size());
      for (BooleanFormula f : formulas) {
        variablesInFormulas.add(fmgr.extractVariables(f));
      }

      for (int i = 0; i < interpolants.size(); i++) {

        Set<String> variablesInA = new HashSet<>();
        for (int j = 0; j <= i; j++) {
          // formula i is in group A
          variablesInA.addAll(variablesInFormulas.get(j));
        }

        Set<String> variablesInB = new HashSet<>();
        for (int j = i+1; j < formulas.size(); j++) {
          // formula i is in group A
          variablesInB.addAll(variablesInFormulas.get(j));
        }

        Set<String> allowedVariables = Sets.intersection(variablesInA, variablesInB).immutableCopy();
        Set<String> variablesInInterpolant = fmgr.extractVariables(interpolants.get(i));

        variablesInInterpolant.removeAll(allowedVariables);

        if (!variablesInInterpolant.isEmpty()) {
          throw new SolverException("Interpolant "  + interpolants.get(i) + " contains forbidden variable(s) " + variablesInInterpolant);
        }
      }

    } finally {
      interpolantVerificationTimer.stop();
    }
  }

  /**
   * Get information about the error path from the solver after the formulas
   * have been proved to be satisfiable.
   *
   * @param f The list of formulas on the path.
   * @param pItpProver The solver.
   * @param elementsOnPath The ARGElements of the paths represented by f.
   * @return Information about the error path, including a satisfying assignment.
   * @throws CPATransferException
   * @throws InterruptedException
   */
  private <T> CounterexampleTraceInfo getErrorPath(List<BooleanFormula> f,
      InterpolatingProverEnvironment<T> pItpProver, Set<ARGState> elementsOnPath)
      throws CPATransferException, InterruptedException {

    // get the branchingFormula
    // this formula contains predicates for all branches we took
    // this way we can figure out which branches make a feasible path
    BooleanFormula branchingFormula = pmgr.buildBranchingFormula(elementsOnPath);

    if (bfmgr.isTrue(branchingFormula)) {
      return CounterexampleTraceInfo.feasible(f, getModel(pItpProver), ImmutableMap.<Integer, Boolean>of());
    }

    // add formula to solver environment
    pItpProver.push(branchingFormula);

    // need to ask solver for satisfiability again,
    // otherwise model doesn't contain new predicates
    boolean stillSatisfiable = !pItpProver.isUnsat();

    if (stillSatisfiable) {
      Model model = getModel(pItpProver);
      return CounterexampleTraceInfo.feasible(f, model, pmgr.getBranchingPredicateValuesFromModel(model));

    } else {
      // this should not happen
      logger.log(Level.WARNING, "Could not get precise error path information because of inconsistent reachingPathsFormula!");

      dumpInterpolationProblem(f);
      dumpFormulaToFile("formula", branchingFormula, f.size());

      return CounterexampleTraceInfo.feasible(f, Model.empty(), ImmutableMap.<Integer, Boolean>of());
    }
  }

  private <T> Model getModel(InterpolatingProverEnvironment<T> pItpProver) {
    try {
      return pItpProver.getModel();
    } catch (SolverException e) {
      logger.log(Level.WARNING, "Solver could not produce model, variable assignment of error path can not be dumped.");
      logger.logDebugException(e);
      return Model.empty();
    }
  }


  /**
   * Helper method to dump a list of formulas to files.
   */
  private void dumpInterpolationProblem(List<BooleanFormula> f) {
    int k = 0;
    for (BooleanFormula formula : f) {
      dumpFormulaToFile("formula", formula, k++);
    }
  }

  private void dumpFormulaToFile(String name, BooleanFormula f, int i) {
    Path dumpFile = formatFormulaOutputFile(name, i);
    fmgr.dumpFormulaToFile(f, dumpFile);
  }

  private Path formatFormulaOutputFile(String formula, int index) {
    return fmgr.formatFormulaOutputFile("interpolation", cexAnalysisTimer.getNumberOfIntervals(), formula, index);
  }

  /**
   * This class encapsulates the used SMT solver for interpolation,
   * and keeps track of the formulas that are currently on the solver stack.
   *
   * An instance of this class can be used for several interpolation queries
   * in a row, and it will try to keep as many formulas as possible in the
   * SMT solver between those queries (so that the solver may reuse information
   * from previous queries, and hopefully might even return similar interpolants).
   *
   * When an instance won't be used anymore, call {@link #close()}.
   *
   * @param <T>
   */
  private class Interpolator<T> {

    private InterpolatingProverEnvironment<T> itpProver;
    private final List<Triple<BooleanFormula, AbstractState, T>> currentlyAssertedFormulas = new ArrayList<>();

    Interpolator() {
      itpProver = newEnvironment();
    }

    @SuppressWarnings("unchecked")
    private InterpolatingProverEnvironment<T> newEnvironment() {
      // This is safe because we don't actually care about the value of T,
      // only the InterpolatingProverEnvironment itself cares about it.
      return (InterpolatingProverEnvironment<T>)factory.newProverEnvironmentWithInterpolation(false);
    }

    /**
     * Counterexample analysis and predicate discovery.
     * @param pFormulas the formulas for the path
     * @param elementsOnPath the ARGElements on the path (may be empty if no branching information is required)
     * @param itpProver interpolation solver used
     * @return counterexample info with predicated information
     * @throws CPAException
     */
    private CounterexampleTraceInfo buildCounterexampleTrace(
        List<BooleanFormula> f,
        List<AbstractState> pAbstractionStates,
        Set<ARGState> elementsOnPath,
        boolean computeInterpolants) throws CPAException, InterruptedException {

      // Check feasibility of counterexample
      logger.log(Level.FINEST, "Checking feasibility of counterexample trace");
      satCheckTimer.start();

      boolean spurious;
      List<T> itpGroupsIds;
      List<Triple<BooleanFormula, AbstractState, Integer>> orderedFormulas;

      if (pAbstractionStates.isEmpty()) {
        pAbstractionStates = new ArrayList<>(Collections.<AbstractState>nCopies(f.size(), null));
      }
      assert pAbstractionStates.size() == f.size() : "each pathFormula must end with an abstract State";

      try {

        if (getUsefulBlocks) {
          f = Collections.unmodifiableList(getUsefulBlocks(f));
        }

        if (dumpInterpolationProblems) {
          dumpInterpolationProblem(f);
        }

        // re-order formulas if needed
        orderedFormulas = orderFormulas(f, pAbstractionStates);
        assert orderedFormulas.size() == f.size();

        // initialize all interpolation group ids with "null"
        itpGroupsIds = new ArrayList<>(Collections.<T>nCopies(f.size(), null));

        // ask solver for satisfiability
        spurious = checkInfeasabilityOfTrace(orderedFormulas, itpGroupsIds);
        assert itpGroupsIds.size() == f.size();
        assert !itpGroupsIds.contains(null); // has to be filled completely

      } finally {
        satCheckTimer.stop();
      }

      logger.log(Level.FINEST, "Counterexample trace is", (spurious ? "infeasible" : "feasible"));


      // Get either interpolants or error path information
      CounterexampleTraceInfo info;
      if (spurious) {

        if (computeInterpolants) {
          List<BooleanFormula> interpolants = getInterpolants(this, itpGroupsIds, orderedFormulas);
          if (verifyInterpolants) {
            verifyInterpolants(interpolants, f, itpProver);
          }

          if (logger.wouldBeLogged(Level.ALL)) {
            logger.log(Level.ALL, "interpolants", Joiner.on("\n").join(interpolants));
          }

          info = CounterexampleTraceInfo.infeasible(interpolants);
        } else {
          info = CounterexampleTraceInfo.infeasibleNoItp();
        }

      } else {
        // this is a real bug
        info = getErrorPath(f, itpProver, elementsOnPath);
      }

      logger.log(Level.ALL, "Counterexample information:", info);

      return info;
    }

    /**
     * Check the satisfiability of a list of formulas, using them in the given order.
     * This method honors the {@link #incrementalCheck} configuration option.
     * It also updates the SMT solver stack and the {@link #currentlyAssertedFormulas}
     * list that is used if {@link #reuseInterpolationEnvironment} is enabled.
     *
     * @param traceFormulas The list of formulas to check, each formula with its index of where it should be added in the list of interpolation groups.
     * @param itpGroupsIds The list where to store the references to the interpolation groups. This is just a list of 'identifiers' for the formulas.
     * @param itpProver The solver to use.
     * @return True if the formulas are unsatisfiable.
     * @throws InterruptedException
     */
    private boolean checkInfeasabilityOfTrace(List<Triple<BooleanFormula, AbstractState, Integer>> traceFormulas,
        List<T> itpGroupsIds) throws InterruptedException {

      // first identify which formulas are already on the solver stack,
      // which formulas need to be removed from the solver stack,
      // and which formulas need to be added to the solver stack
      ListIterator<Triple<BooleanFormula, AbstractState, Integer>> todoIterator = traceFormulas.listIterator();
      ListIterator<Triple<BooleanFormula, AbstractState, T>> assertedIterator = currentlyAssertedFormulas.listIterator();

      int firstBadIndex = -1; // index of first mis-matching formula in both lists

      while (assertedIterator.hasNext()) {
        Triple<BooleanFormula, AbstractState, T> assertedFormula = assertedIterator.next();

        if (!todoIterator.hasNext()) {
          firstBadIndex = assertedIterator.previousIndex();
          break;
        }

        Triple<BooleanFormula, AbstractState, Integer> todoFormula = todoIterator.next();

        if (todoFormula.getFirst().equals(assertedFormula.getFirst())) {
          // formula is already in solver stack in correct location
          @SuppressWarnings("unchecked")
          T itpGroupId = assertedFormula.getThird();
          itpGroupsIds.set(todoFormula.getThird(), itpGroupId);

        } else {
          firstBadIndex = assertedIterator.previousIndex();
          // rewind iterator by one so that todoFormula will be added to stack
          todoIterator.previous();
          break;
        }
      }

      // now remove the formulas from the solver stack where necessary
      if (firstBadIndex == -1) {
        // solver stack was already empty, nothing do to

      } else if (firstBadIndex == 0) {
        // Create a new environment instead of cleaning up the old one
        // if no formulas need to be reused.
        itpProver.close();
        itpProver = newEnvironment();
        currentlyAssertedFormulas.clear();

      } else {
        assert firstBadIndex > 0;
        // list with all formulas on solver stack that we need to remove
        // (= remaining formulas in currentlyAssertedFormulas list)
        List<Triple<BooleanFormula, AbstractState, T>> toDeleteFormulas =
            currentlyAssertedFormulas.subList(firstBadIndex,
                                              currentlyAssertedFormulas.size());

        // remove formulas from solver stack
        for (int i = 0; i < toDeleteFormulas.size(); i++) {
          itpProver.pop();
        }
        toDeleteFormulas.clear(); // this removes from currentlyAssertedFormulas

        reusedFormulasOnSolverStack += currentlyAssertedFormulas.size();
      }

      boolean isStillFeasible = true;

      if (incrementalCheck && !currentlyAssertedFormulas.isEmpty()) {
        if (itpProver.isUnsat()) {
          isStillFeasible = false;
        }
      }

      // add remaining formulas to the solver stack
      while (todoIterator.hasNext()) {
        Triple<BooleanFormula, AbstractState, Integer> p = todoIterator.next();
        BooleanFormula f = p.getFirst();
        AbstractState state = p.getSecond();
        int index = p.getThird();

        assert itpGroupsIds.get(index) == null;
        T itpGroupId = itpProver.push(f);
        itpGroupsIds.set(index, itpGroupId);
        currentlyAssertedFormulas.add(Triple.of(f, state, itpGroupId));

        if (incrementalCheck && isStillFeasible && !bfmgr.isTrue(f)) {
          if (itpProver.isUnsat()) {
            // We need to iterate through the full loop
            // to add all formulas, but this prevents us from doing further sat checks.
            isStillFeasible = false;
          }
        }
      }

      assert Iterables.elementsEqual(from(traceFormulas).transform(Triple.getProjectionToFirst()),
              from(currentlyAssertedFormulas).transform(Triple.getProjectionToFirst()));

      if (incrementalCheck) {
        // we did unsat checks
        return !isStillFeasible;
      } else {
        return itpProver.isUnsat();
      }
    }

    private void close() {
      itpProver.close();
      itpProver = null;
      currentlyAssertedFormulas.clear();
    }
  }
}