// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2022 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.cpa.datarace;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AExpressionAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.AExpressionStatement;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallAssignmentStatement;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCallStatement;
import org.sosy_lab.cpachecker.cfa.ast.AStatement;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializer;
import org.sosy_lab.cpachecker.cfa.ast.c.CInitializerExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CUnaryExpression;
import org.sosy_lab.cpachecker.cfa.ast.c.CUnaryExpression.UnaryOperator;
import org.sosy_lab.cpachecker.cfa.ast.c.CVariableDeclaration;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.AReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.AssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionCallEdge;
import org.sosy_lab.cpachecker.core.defaults.SingleEdgeTransferRelation;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.cpa.invariants.EdgeAnalyzer;
import org.sosy_lab.cpachecker.cpa.threading.GlobalAccessChecker;
import org.sosy_lab.cpachecker.cpa.threading.ThreadingState;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;
import org.sosy_lab.cpachecker.exceptions.UnsupportedCodeException;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.states.MemoryLocation;

public class DataRaceTransferRelation extends SingleEdgeTransferRelation {

  private static final ImmutableSet<String> UNSUPPORTED_FUNCTIONS =
      ImmutableSet.of(
          "pthread_trylock",
          "pthread_rwlock_rdlock",
          "pthread_rwlock_timedrdlock",
          "pthread_rwlock_timedwrlock",
          "pthread_rwlock_wrlock");

  private static final ImmutableSet<String> THREAD_SAFE_FUNCTIONS =
      ImmutableSet.of(
          "pthread_mutex_lock",
          "pthread_mutex_unlock",
          "pthread_create",
          "pthread_mutexattr_init",
          "pthread_mutexattr_settype",
          "pthread_mutex_init",
          "pthread_rwlock_wrlock",
          "pthread_rwlock_unlock",
          "pthread_rwlock_rdlock",
          "pthread_mutex_trylock",
          "pthread_join",
          "pthread_cond_wait",
          "pthread_cond_signal",
          "pthread_mutex_destroy",
          "pthread_attr_init",
          "pthread_attr_setdetachstate",
          "pthread_attr_destroy",
          "pthread_cond_init",
          "pthread_cond_destroy",
          "pthread_self",
          "pthread_cleanup_push",
          "pthread_cleanup_pop",
          "pthread_cond_broadcast",
          "pthread_getspecific",
          "pthread_setspecific",
          "pthread_key_create",
          "pthread_exit",
          "pthread_equal",
          "pthread_mutexattr_destroy");

  private final EdgeAnalyzer edgeAnalyzer;
  private final GlobalAccessChecker globalAccessChecker = new GlobalAccessChecker();

  public DataRaceTransferRelation(EdgeAnalyzer pEdgeAnalyzer) {
    edgeAnalyzer = pEdgeAnalyzer;
  }

  @Override
  public Collection<? extends AbstractState> getAbstractSuccessorsForEdge(
      AbstractState pState, Precision precision, CFAEdge cfaEdge)
      throws CPATransferException, InterruptedException {
    // Can only update state with info from ThreadingCPA
    return ImmutableSet.of(pState);
  }

  @Override
  public Collection<? extends AbstractState> strengthen(
      AbstractState pState,
      Iterable<AbstractState> otherStates,
      @Nullable CFAEdge cfaEdge,
      Precision precision)
      throws CPATransferException, InterruptedException {
    if (cfaEdge == null || !globalAccessChecker.hasGlobalAccess(cfaEdge)) {
      return ImmutableSet.of(pState);
    }
    DataRaceState state = (DataRaceState) pState;
    ImmutableSet.Builder<DataRaceState> strengthenedStates = ImmutableSet.builder();

    for (ThreadingState threadingState :
        AbstractStates.projectToType(otherStates, ThreadingState.class)) {
      Map<String, ThreadInfo> threads = state.getThreads();
      Set<String> threadIds = threadingState.getThreadIds();
      String activeThread = getActiveThread(cfaEdge, threadingState);
      Set<String> locks = threadingState.getLocksForThread(activeThread);
      ImmutableMap<String, ThreadInfo> newThreads = getNewThreads(threads, threadIds, activeThread);
      Set<MemoryAccess> newMemoryAccesses = getNewAccesses(threads, activeThread, cfaEdge, locks);

      Set<MemoryAccess> memoryAccesses;
      if (newThreads.size() == 1) {
        // Only a single thread, no need to track memory accesses
        memoryAccesses = ImmutableSet.of();
      } else {
        ImmutableSet.Builder<MemoryAccess> memoryAccessBuilder = ImmutableSet.builder();
        memoryAccessBuilder.addAll(newMemoryAccesses);
        for (MemoryAccess access : state.getMemoryAccesses()) {
          if (threadIds.contains(access.getThreadId())) {
            memoryAccessBuilder.add(access);
          }
        }
        memoryAccesses = memoryAccessBuilder.build();
      }

      boolean hasDataRace = state.hasDataRace();
      for (MemoryAccess access : memoryAccesses) {
        if (hasDataRace) {
          break;
        }
        // In particular, this skips all new memory accesses
        if (access.getThreadId().equals(activeThread)) {
          continue;
        }
        for (MemoryAccess newAccess : newMemoryAccesses) {
          if (access.getMemoryLocation().equals(newAccess.getMemoryLocation())
              && Sets.intersection(access.getLocks(), newAccess.getLocks()).isEmpty()
              && (access.isWrite() || newAccess.isWrite())
              && !access.happensBefore(newAccess, threads)) {
            hasDataRace = true;
            break;
          }
        }
      }
      strengthenedStates.add(new DataRaceState(memoryAccesses, newThreads, hasDataRace));
    }

    return strengthenedStates.build();
  }

  private Set<MemoryAccess> getNewAccesses(
      Map<String, ThreadInfo> threads, String activeThread, CFAEdge edge, Set<String> locks)
      throws UnsupportedCodeException {
    ImmutableSet.Builder<MemoryLocation> accessedLocationBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<MemoryLocation> modifiedLocationBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<MemoryAccess> newAccessBuilder = ImmutableSet.builder();

    switch (edge.getEdgeType()) {
      case AssumeEdge:
        AssumeEdge assumeEdge = (AssumeEdge) edge;
        accessedLocationBuilder.addAll(
            edgeAnalyzer.getInvolvedVariableTypes(assumeEdge.getExpression(), assumeEdge).keySet());
        break;
      case DeclarationEdge:
        ADeclarationEdge declarationEdge = (ADeclarationEdge) edge;
        ADeclaration declaration = declarationEdge.getDeclaration();
        if (declaration instanceof CVariableDeclaration) {
          CVariableDeclaration variableDeclaration = (CVariableDeclaration) declaration;
          MemoryLocation declaredVariable =
              MemoryLocation.fromQualifiedName(variableDeclaration.getQualifiedName());
          CInitializer initializer = variableDeclaration.getInitializer();
          newAccessBuilder.add(
              new MemoryAccess(
                  activeThread,
                  threads.get(activeThread).getEpoch(),
                  declaredVariable,
                  initializer != null,
                  locks));
          if (initializer != null) {
            if (initializer instanceof CInitializerExpression
                && ((CInitializerExpression) initializer).getExpression()
                    instanceof CUnaryExpression) {
              CUnaryExpression initializerExpression =
                  (CUnaryExpression) ((CInitializerExpression) initializer).getExpression();
              if (initializerExpression.getOperator().equals(UnaryOperator.AMPER)) {
                // Address-of is not considered accessing its operand
                break;
              }
            }
            accessedLocationBuilder.addAll(
                edgeAnalyzer.getInvolvedVariableTypes(initializer, declarationEdge).keySet());
          }
        }
        break;
      case FunctionCallEdge:
        FunctionCallEdge functionCallEdge = (FunctionCallEdge) edge;
        String functionName =
            functionCallEdge.getFunctionCallExpression().getDeclaration().getName();
        if (UNSUPPORTED_FUNCTIONS.contains(functionName)) {
          throw new UnsupportedCodeException(
              "DataRaceCPA does not support function " + functionName, edge);
        }
        if (functionCallEdge.getFunctionCall() instanceof AFunctionCallAssignmentStatement) {
          AFunctionCallAssignmentStatement functionCallAssignmentStatement =
              (AFunctionCallAssignmentStatement) functionCallEdge.getFunctionCall();
          if (THREAD_SAFE_FUNCTIONS.contains(functionName)) {
            accessedLocationBuilder.addAll(
                edgeAnalyzer
                    .getInvolvedVariableTypes(
                        functionCallAssignmentStatement.getLeftHandSide(), functionCallEdge)
                    .keySet());
          } else {
            accessedLocationBuilder.addAll(
                edgeAnalyzer
                    .getInvolvedVariableTypes(functionCallAssignmentStatement, functionCallEdge)
                    .keySet());
          }
        } else {
          if (!THREAD_SAFE_FUNCTIONS.contains(functionName)) {
            for (AExpression argument : functionCallEdge.getArguments()) {
              accessedLocationBuilder.addAll(
                  edgeAnalyzer.getInvolvedVariableTypes(argument, functionCallEdge).keySet());
            }
          }
        }
        break;
      case ReturnStatementEdge:
        AReturnStatementEdge returnStatementEdge = (AReturnStatementEdge) edge;
        if (returnStatementEdge.getExpression().isPresent()) {
          AExpression returnExpression = returnStatementEdge.getExpression().get();
          accessedLocationBuilder.addAll(
              edgeAnalyzer
                  .getInvolvedVariableTypes(returnExpression, returnStatementEdge)
                  .keySet());
        }
        break;
      case StatementEdge:
        AStatementEdge statementEdge = (AStatementEdge) edge;
        AStatement statement = statementEdge.getStatement();
        if (statement instanceof AExpressionAssignmentStatement) {
          AExpressionAssignmentStatement expressionAssignmentStatement =
              (AExpressionAssignmentStatement) statement;
          if (expressionAssignmentStatement.getRightHandSide() instanceof CUnaryExpression
              && ((CUnaryExpression) expressionAssignmentStatement.getRightHandSide())
                  .getOperator()
                  .equals(UnaryOperator.AMPER)) {
            // Address-of is not considered accessing its operand
            accessedLocationBuilder.addAll(
                edgeAnalyzer
                    .getInvolvedVariableTypes(
                        expressionAssignmentStatement.getLeftHandSide(), statementEdge)
                    .keySet());
          } else {
            accessedLocationBuilder.addAll(
                edgeAnalyzer
                    .getInvolvedVariableTypes(expressionAssignmentStatement, statementEdge)
                    .keySet());
          }
          modifiedLocationBuilder.addAll(
              edgeAnalyzer
                  .getInvolvedVariableTypes(
                      expressionAssignmentStatement.getLeftHandSide(), statementEdge)
                  .keySet());
        } else if (statement instanceof AExpressionStatement) {
          accessedLocationBuilder.addAll(
              edgeAnalyzer
                  .getInvolvedVariableTypes(
                      ((AExpressionStatement) statement).getExpression(), statementEdge)
                  .keySet());
        } else if (statement instanceof AFunctionCallAssignmentStatement) {
          AFunctionCallAssignmentStatement functionCallAssignmentStatement =
              (AFunctionCallAssignmentStatement) statement;
          functionName =
              functionCallAssignmentStatement
                  .getFunctionCallExpression()
                  .getDeclaration()
                  .getName();
          if (UNSUPPORTED_FUNCTIONS.contains(functionName)) {
            throw new UnsupportedCodeException(
                "DataRaceCPA does not support function " + functionName, edge);
          }
          if (THREAD_SAFE_FUNCTIONS.contains(functionName)) {
            accessedLocationBuilder.addAll(
                edgeAnalyzer
                    .getInvolvedVariableTypes(
                        functionCallAssignmentStatement.getLeftHandSide(), statementEdge)
                    .keySet());
          } else {
            accessedLocationBuilder.addAll(
                edgeAnalyzer
                    .getInvolvedVariableTypes(functionCallAssignmentStatement, statementEdge)
                    .keySet());
          }
          modifiedLocationBuilder.addAll(
              edgeAnalyzer
                  .getInvolvedVariableTypes(
                      functionCallAssignmentStatement.getLeftHandSide(), statementEdge)
                  .keySet());
        } else if (statement instanceof AFunctionCallStatement) {
          AFunctionCallStatement functionCallStatement = (AFunctionCallStatement) statement;
          functionName =
              functionCallStatement.getFunctionCallExpression().getDeclaration().getName();
          if (UNSUPPORTED_FUNCTIONS.contains(functionName)) {
            throw new UnsupportedCodeException(
                "DataRaceCPA does not support function " + functionName, edge);
          }
          if (!THREAD_SAFE_FUNCTIONS.contains(functionName)) {
            for (AExpression expression :
                functionCallStatement.getFunctionCallExpression().getParameterExpressions()) {
              accessedLocationBuilder.addAll(
                  edgeAnalyzer.getInvolvedVariableTypes(expression, statementEdge).keySet());
            }
          }
        }
        break;
      case FunctionReturnEdge:
      case BlankEdge:
      case CallToReturnEdge:
        break;
      default:
        throw new AssertionError("Unknown edge type: " + edge.getEdgeType());
    }

    Set<MemoryLocation> accessedLocations = accessedLocationBuilder.build();
    Set<MemoryLocation> modifiedLocations = modifiedLocationBuilder.build();
    assert accessedLocations.containsAll(modifiedLocations);

    for (MemoryLocation location : accessedLocations) {
      newAccessBuilder.add(
          new MemoryAccess(
              activeThread,
              threads.get(activeThread).getEpoch(),
              location,
              modifiedLocations.contains(location),
              locks));
    }
    return newAccessBuilder.build();
  }

  private ImmutableMap<String, ThreadInfo> getNewThreads(
      Map<String, ThreadInfo> threads, Set<String> threadIds, String activeThread) {
    Set<String> added = Sets.difference(threadIds, threads.keySet());
    assert added.size() < 2 : "Multiple thread creations in same step not supported";
    Set<String> removed = Sets.difference(threads.keySet(), threadIds);

    ImmutableMap.Builder<String, ThreadInfo> threadsBuilder = ImmutableMap.builder();
    if (added.isEmpty()) {
      for (Entry<String, ThreadInfo> entry : threads.entrySet()) {
        if (!removed.contains(entry.getKey())) {
          threadsBuilder.put(entry);
        }
      }
    } else {
      String threadId = added.iterator().next();
      ThreadInfo parent = threads.get(activeThread);
      threadsBuilder.put(threadId, new ThreadInfo(parent, threadId, 0, parent.getEpoch() + 1));
      threadsBuilder.put(parent.getName(), parent.increaseEpoch());
      for (Entry<String, ThreadInfo> entry : threads.entrySet()) {
        if (!(removed.contains(entry.getKey()) || entry.getKey().equals(activeThread))) {
          threadsBuilder.put(entry);
        }
      }
    }
    return threadsBuilder.buildOrThrow();
  }

  /**
   * Search for the thread where the given edge is available.
   *
   * <p>This method is necessary, because neither ThreadingState::getActiveThread nor
   * ThreadingTransferRelation::getActiveThread are guaranteed to give the correct result during
   * strengthening.
   */
  private String getActiveThread(final CFAEdge cfaEdge, final ThreadingState threadingState) {
    for (String id : threadingState.getThreadIds()) {
      if (Iterables.contains(threadingState.getThreadLocation(id).getIngoingEdges(), cfaEdge)) {
        return id;
      }
    }
    throw new AssertionError("Unable to determine active thread");
  }
}
