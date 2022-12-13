// This file is part of CPAchecker,
// a tool for configurable software verification:
// https://cpachecker.sosy-lab.org
//
// SPDX-FileCopyrightText: 2007-2020 Dirk Beyer <https://www.sosy-lab.org>
//
// SPDX-License-Identifier: Apache-2.0

package org.sosy_lab.cpachecker.util.cwriter;

import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.FluentIterable.from;
import static java.util.Collections.singletonList;
import static org.sosy_lab.common.Appenders.concat;
import static org.sosy_lab.common.Appenders.forIterable;
import static org.sosy_lab.cpachecker.util.cwriter.LoopCollectingEdgeVisitor.getLoopsOfNode;

import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.Appender;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.AReturnStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.model.FunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.FunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.FunctionExitNode;
import org.sosy_lab.cpachecker.cfa.model.c.CAssumeEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionCallEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionEntryNode;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionReturnEdge;
import org.sosy_lab.cpachecker.cfa.model.c.CFunctionSummaryEdge;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.path.ARGPath;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFAUtils;
import org.sosy_lab.cpachecker.util.LoopStructure;
import org.sosy_lab.cpachecker.util.LoopStructure.Loop;
import org.sosy_lab.cpachecker.util.Pair;

/**
 * This class translates a given ARGpath into c-code. The created code consists partly of unrolled
 * parts of the usual program and recreated / uprolled parts of the given ARG. Therefore this
 * translator is a mix of the code generated by the RealCTranslator and the real program (there may
 * be additional temporary variables, and also some functions / variables may be called different)
 */
public class PathToCWithLoopsTranslator extends PathTranslator {

  private static final Pattern uniqueFunction = Pattern.compile(".*_[0-9]+(.*)");

  private final LoopStructure loopStructure;
  private final Map<Loop, Set<ARGState>> loopsInPathToRecreate;
  private Deque<String> callStack = new ArrayDeque<>();
  private Map<String, Pair<CFunctionEntryNode, FunctionBody>> nonUniqueFunctions = new HashMap<>();
  private int labelCounter = 0;

  // all necessary stacks / queues / maps for recreatig loops and functions
  private Map<Loop, String> loopInLabels = new HashMap<>();
  private Map<Loop, CFANode> loopToHead = new HashMap<>();
  private Map<Loop, String> loopOutLabels = new HashMap<>();
  private Set<CFAEdge> handledEdges = new HashSet<>();
  private Set<String> handledFunctions = new HashSet<>();
  private Map<CFAEdge, String> ifOutLabels = new HashMap<>();
  private Deque<Map<CFANode, String>> ifOutLabelEnd = new ArrayDeque<>();
  private Set<CFANode> ifThenHandled = new HashSet<>();
  private Map<CFAEdge, String> ifElseLabels = new HashMap<>();

  private String currentFunctionName = "";

  private PathToCWithLoopsTranslator(CFA pCFA, Map<Loop, Set<ARGState>> pLoopsInPathToRecreate) {
    loopStructure = pCFA.getLoopStructure().orElseThrow();
    loopsInPathToRecreate = pLoopsInPathToRecreate;
  }

  /**
   * Transform a single linear path into C code. The path needs to be loop free.
   *
   * <p>TODO: implement the proper handling of labels/gotos, currently the created code is not
   * correct in all cases when gotos are used.
   *
   * <p>TODO: Detect loops in the paths and signal an error. Currently when there are loops, the
   * generated C code is invalid because there is a goto to a missing label.
   *
   * @param pPath The path.
   * @return An appender that generates C code.
   */
  public static Appender translateSinglePath(ARGPath pPath, CFA cfa, Configuration config)
      throws InvalidConfigurationException {
    PathToCTranslator translator = new PathToCTranslator();

    // at first we fetch all loops that we want to uproll
    LoopCollectingEdgeVisitor loopCollectingVisitor =
        new LoopCollectingEdgeVisitor(cfa.getLoopStructure().orElseThrow(), config);
    translator.translateSinglePath0(pPath, loopCollectingVisitor);
    Map<Loop, Set<ARGState>> loopsInPathToRecreate = loopCollectingVisitor.getRelevantLoops();

    // new translator, now for creating the c-code
    PathToCWithLoopsTranslator loopTranslator =
        new PathToCWithLoopsTranslator(cfa, loopsInPathToRecreate);
    loopTranslator.translateSinglePath0(pPath, new DefaultEdgeVisitor(loopTranslator));

    return loopTranslator.generateCCode();
  }

  @Override
  protected Appender generateCCode() {
    // proper order and c-code without warnings
    mGlobalDefinitionsList.add(0, "#include <stdlib.h>\n");
    mGlobalDefinitionsList.add(1, "#include <time.h>\n");
    mGlobalDefinitionsList.add(2, "#define __VERIFIER_nondet_int() rand()");

    mGlobalDefinitionsList.remove("int __VERIFIER_nondet_int()");

    mFunctionDecls.add("int main() {\n  srand(time(NULL));\n  return main_0();\n}");

    // now create the usual program without the non-unique functions
    Appender app = super.generateCCode();

    // and generate the non-unique functions here
    return createNonUniqueFunctions(app);
  }

  private Appender createNonUniqueFunctions(Appender app) {
    List<FunctionBody> finishedBodies = new ArrayList<>();
    for (Pair<CFunctionEntryNode, FunctionBody> p : nonUniqueFunctions.values()) {
      FunctionBody body = p.getSecond();
      body.write(
          recreateFunction(
              p.getFirst(),
              p.getFirst(),
              p.getFirst().getExitNode().orElse(null),
              body.getCurrentBlock()));
      finishedBodies.add(body);
    }
    return concat(app, forIterable(Joiner.on('\n'), finishedBodies));
  }

  @Override
  protected String startFunction(
      final ARGState firstFunctionElement,
      Deque<FunctionBody> functionStack,
      final CFANode predecessor) {
    // create the first stack element using the first element of the function
    CFunctionEntryNode functionStartNode = extractFunctionCallLocation(firstFunctionElement);
    String functionName = functionStartNode.getFunctionName();

    // only use a unique name where we are not inside a relevant loop
    if (loopsInPathToRecreate.isEmpty()
        || loopsInPathToRecreate.keySet().stream()
            .noneMatch(loop -> loop.getLoopNodes().contains(predecessor))) {
      functionName = getFreshFunctionName(functionStartNode);
    }

    String lFunctionHeader =
        functionStartNode.getFunctionDefinition().getType().toASTString(functionName);
    // lFunctionHeader is for example "void foo_99(int a)"

    // create a new function
    FunctionBody newFunction = new FunctionBody(firstFunctionElement.getStateId(), lFunctionHeader);

    // register function if necessary
    if (!mFunctionDecls.contains(lFunctionHeader + ";")) {
      mFunctionDecls.add(lFunctionHeader + ";");
      if (uniqueFunction.matcher(lFunctionHeader).matches()) {
        mFunctionBodies.add(newFunction);
        functionStack.push(newFunction); // add function to current stack
      } else {
        nonUniqueFunctions.put(lFunctionHeader, Pair.of(functionStartNode, newFunction));
      }
    }

    // push to callstack after the if-clause before, we allow several calls
    // to the same function but we do not allow them to be on the same callstack
    callStack.push(lFunctionHeader);
    assert callStack.size() == new HashSet<>(callStack).size() : "Recursive function call found";

    return functionName;
  }

  @Override
  protected void processEdge(
      ARGState childElement, CFAEdge edge, Deque<FunctionBody> functionStack) {
    // we don't need to handle this edge again
    if (handledFunctions.contains(callStack.peek()) && edge instanceof CFunctionReturnEdge) {
      callStack.pop();
      return;
    }

    // we don't need to handle this edge again
    if (handledEdges.contains(edge) || handledFunctions.contains(callStack.peek())) {
      return;
    }

    if (loopsInPathToRecreate == null) {
      super.processEdge(childElement, edge, functionStack);
    } else {
      processEdge0(childElement, edge, functionStack);
    }
  }

  /** Processes an edge of the CFA and will write code to the output function body. */
  private void processEdge0(
      ARGState childElement, CFAEdge edge, Deque<FunctionBody> functionStack) {
    FunctionBody currentFunction = functionStack.peek();

    if (childElement.isTarget()) {
      currentFunction.write(getTargetState());
    }

    // handle the edge

    if (edge instanceof CFunctionCallEdge) {

      // if this function is already handled we need to quit here and just push
      // the function onto the callstack
      if (handledFunctions.contains(edge.getSuccessor().getFunctionName())) {
        callStack.push(edge.getSuccessor().getFunctionName());
        currentFunctionName = edge.getSuccessor().getFunctionName();
        return;
      }
      // if this is a function call edge we need to create a new state and push
      // it to the topmost stack to represent the function

      // create function and put in onto stack
      String freshFunctionName = startFunction(childElement, functionStack, edge.getPredecessor());
      currentFunctionName = freshFunctionName;

      // write summary edge to the caller site (with the new unique function name)
      currentFunction.write(processFunctionCall(edge, freshFunctionName));

    } else if (edge instanceof CFunctionReturnEdge) {
      // only pop from functionStack when we have a unique function
      // and the functionreturn has also to be matchin to the current function we are
      // in
      if (callStack
          .peek()
          .contains(((CFunctionReturnEdge) edge).getFunctionEntry().getFunctionName())) {
        if (uniqueFunction.matcher(callStack.peek()).matches()) {
          functionStack.pop();
        }
        callStack.pop();
      }
      currentFunctionName = callStack.peek();

    } else {
      // only write edges that are not from functions which we have to
      // write completely, this will be done afterwards
      if (uniqueFunction.matcher(callStack.peek()).matches()) {
        currentFunction.write(
            processSimpleEdge0(edge, currentFunction.getCurrentBlock(), childElement));
      }
    }
  }

  private String processSimpleEdge0(
      CFAEdge pCFAEdge, BasicBlock currentBlock, final ARGState state) {

    CFANode succ = pCFAEdge.getSuccessor();
    List<Loop> loopsAfter =
        from(getLoopsOfNode(loopStructure, succ))
            .filter(loopsInPathToRecreate::containsKey)
            .toList();

    // we do not go into a loop that has to be uprolled, so just continue normally
    if (loopsAfter.isEmpty()
        || !loopsInPathToRecreate.get(loopsAfter.get(loopsAfter.size() - 1)).contains(state)) {
      return processSimpleWithLoop(pCFAEdge, currentBlock, "").trim();
    } else {
      return recreateLoop(pCFAEdge, currentBlock, loopsAfter).getFirst();
    }
  }

  /**
   * Recreates a function from the currentStartNode up to the given untilNode.
   *
   * @param functionEntryNode the entry node into this function
   * @param currentStartNode the node from where the recreation should start
   * @param untilNode the node where the recreation should end
   * @param block the current basic block
   * @return the c-code of the recreated function part
   */
  private String recreateFunction(
      FunctionEntryNode functionEntryNode,
      CFANode currentStartNode,
      @Nullable CFANode untilNode,
      BasicBlock block) {
    StringBuilder wholeFunction = new StringBuilder();

    Deque<CFAEdge> nextEdge = new ArrayDeque<>();
    // the function entry node always has exactly one following edge
    // which is labeled "function start dummy edge"
    // therefore it is safe to only use this edge without further checks
    nextEdge.offer(currentStartNode.getLeavingEdge(0));

    while (!nextEdge.isEmpty()) {
      CFAEdge currentEdge = nextEdge.poll();
      wholeFunction.append(processSimpleWithLoop(currentEdge, block, ""));

      if (Objects.equals(currentEdge.getSuccessor(), untilNode)) {
        return wholeFunction.toString();
      }

      // there is no successor after this node
      // so just look if we have more edges to do
      if (currentEdge instanceof AReturnStatementEdge) {
        continue;
      }

      CFANode successor = currentEdge.getSuccessor();

      // check if we already encountered the current edge before
      if (handledEdges.contains(currentEdge)) {
        continue;
      }

      FluentIterable<Loop> currentLoops = from(getLoopsOfNode(loopStructure, successor));

      // we found a loop which has to be handled
      if (!currentLoops.isEmpty()) {
        Pair<String, CFAEdge> tmp = recreateLoop(currentEdge, block, currentLoops.toList());
        // there should be only one successor on a leaving edge from a loop
        assert tmp.getSecond().getSuccessor().getNumLeavingEdges() == 1;
        nextEdge.offer(tmp.getSecond().getSuccessor().getLeavingEdge(0));
        wholeFunction.append(tmp.getFirst());

        // we found an if-statement
      } else if (successor.getNumLeavingEdges() > 1) {
        Pair<String, CFAEdge> tmp = recreateIf(currentEdge, block, functionEntryNode);
        nextEdge.offer(tmp.getSecond());
        wholeFunction.append(tmp.getFirst());

        // a normal edge
      } else {
        CFAEdge leavingEdge = successor.getLeavingEdge(0);

        // use the summary edge so that we write only the code of one function
        if (leavingEdge instanceof CFunctionCallEdge) {
          leavingEdge = successor.getLeavingSummaryEdge();
        }

        nextEdge.offer(leavingEdge);
      }
    }
    return wholeFunction.toString();
  }

  /**
   * Recreates the code of an if-clause with gotos
   *
   * @param pCFAEdge the edge to the head of the if-clause
   * @param currentBlock the current block
   * @return the complete c-code for the recreated if-clause
   */
  private Pair<String, CFAEdge> recreateIf(
      CFAEdge pCFAEdge, BasicBlock currentBlock, FunctionEntryNode entryNode) {
    StringBuilder ifString = new StringBuilder();

    CFAEdge branch1 = pCFAEdge.getSuccessor().getLeavingEdge(0);
    CFAEdge branch2 = pCFAEdge.getSuccessor().getLeavingEdge(1);

    // calling findEndOfBranches only makes sense if the function exit has entering edges
    FunctionExitNode functionExitNode = entryNode.getExitNode().orElseThrow();
    CFANode ifEnd =
        findEndOfBranches(
            singletonList(functionExitNode),
            pCFAEdge.getPredecessor(),
            branch1.getSuccessor(),
            branch2.getSuccessor());

    String elseLabel = createFreshLabelForIf(branch2, ifEnd);
    String outLabel = ifOutLabelEnd.peek().get(ifEnd);
    ifString
        .append(processSimpleWithLoop(branch1, currentBlock, elseLabel))
        .append(recreateFunction(entryNode, branch1.getSuccessor(), ifEnd, currentBlock))
        .append("goto ")
        .append(outLabel)
        .append(";\n")
        .append(elseLabel)
        .append(": ;\n")
        .append(recreateFunction(entryNode, branch2.getSuccessor(), ifEnd, currentBlock))
        .append(outLabel)
        .append(": ;\n");

    // there should only be one leaving edge from the mergepoint
    assert ifEnd.getNumLeavingEdges() == 1;
    return Pair.of(ifString.toString(), ifEnd.getLeavingEdge(0));
  }

  /**
   * Recreates the code of one (or more nested) loops with gotos.
   *
   * @param pCFAEdge the edge into the loop
   * @param currentBlock the current block
   * @param loopsAfter the loops which we are in after the edge
   * @return the complete c-code for the recreated loop
   */
  private Pair<String, CFAEdge> recreateLoop(
      CFAEdge pCFAEdge, BasicBlock currentBlock, List<Loop> loopsAfter) {
    // clear all necessary things
    resetLoopAndIfMaps();

    CFAEdge lastEdge = null;

    // start actual loop recreation
    StringBuilder wholeLoopString = new StringBuilder();

    // we go into a loop thus we have to uproll it right now, and add all
    // handled edges to the handledEdges list, so they wont occur twice in the
    // generated c code

    // this should be already handled by the handledEdges check at the beginning
    // of the processEdge method
    assert loopsAfter.get(loopsAfter.size() - 1).getIncomingEdges().contains(pCFAEdge);

    Loop loop = loopsAfter.get(loopsAfter.size() - 1);

    // create necessary mappings
    String labelStayInLoop = createFreshLabelForLoop(pCFAEdge, loop);

    // uproll loop and write code
    wholeLoopString.append(labelStayInLoop).append(": ;\n");
    Deque<CFAEdge> edgesToHandle = new ArrayDeque<>();
    edgesToHandle.offer(pCFAEdge);

    Deque<Loop> loopStack = new ArrayDeque<>();
    loopStack.push(loop);
    Deque<CFAEdge> ifStack = new ArrayDeque<>();
    Deque<CFAEdge> outOfLoopEdgesStack = new ArrayDeque<>();

    while ((!edgesToHandle.isEmpty() || !outOfLoopEdgesStack.isEmpty() || !ifStack.isEmpty())) {

      // all nodes from the current loop handled, so we can go on to the
      // next one
      if (edgesToHandle.isEmpty()) {
        // at first we need to handle ifs
        if (!ifStack.isEmpty()) {
          edgesToHandle.offer(ifStack.pop());
          wholeLoopString
              .append("goto ")
              .append(ifOutLabels.get(edgesToHandle.peek()))
              .append(";\n")
              .append(ifElseLabels.get(edgesToHandle.peek()))
              .append(": ;\n");
        } else {
          edgesToHandle.offer(outOfLoopEdgesStack.pop());
          Loop oldLoop = loopStack.pop();
          wholeLoopString
              .append("goto ")
              .append(loopInLabels.get(oldLoop))
              .append(";\n")
              .append(loopOutLabels.get(oldLoop))
              .append(": ;\n");
        }
      }

      CFANode currentEdgePredecessor = edgesToHandle.peek().getPredecessor();
      handleIfOutLabels(wholeLoopString, currentEdgePredecessor);

      // only continue if we didn't already visit this edge
      if (handledEdges.contains(edgesToHandle.peek())) {
        edgesToHandle.pop();
        continue;
      }

      CFAEdge currentEdge = edgesToHandle.pop();
      handledEdges.add(currentEdge);
      FluentIterable<CFAEdge> leaving =
          CFAUtils.leavingEdges(currentEdge.getSuccessor())
              .filter(not(instanceOf(FunctionCallEdge.class)));

      // there was a function call, we need to replace it with the correct successor
      // as we are sure that there is only one, this is safe, we also don't
      // need to update loops here
      if (leaving.isEmpty()) {
        CFAEdge realLeavingEdge = currentEdge.getSuccessor().getLeavingEdge(0);
        CFAEdge leavingSummaryEdge = currentEdge.getSuccessor().getLeavingSummaryEdge();

        wholeLoopString.append(processSimpleWithLoop(realLeavingEdge, currentBlock, ""));
        handledFunctions.add(realLeavingEdge.getSuccessor().getFunctionName());
        leaving = leaving.append(leavingSummaryEdge);

        // no function call just and ordinary statement, add it as it is
        // to the loopString
      } else if (leaving.size() == 1) {
        wholeLoopString.append(processSimpleWithLoop(leaving.get(0), currentBlock, ""));
      }

      // only one successor, to handle
      // we need to check the loops so that we know if we need
      // to update the loopStack, or only the handledEdges
      if (leaving.size() == 1) {
        CFAEdge onlyEdge = leaving.get(0);

        // this is an edge from inside the loop back to the loop
        if (Objects.equals(loopToHead.get(loopStack.peek()), onlyEdge.getSuccessor())
            && !loopStack.peek().getIncomingEdges().contains(onlyEdge)) {
          handledEdges.add(onlyEdge);

          handleIfOutLabels(wholeLoopString, onlyEdge.getPredecessor());
        } else {
          edgesToHandle.offer(onlyEdge);
          updateLoopStack(wholeLoopString, loopStack, onlyEdge);
        }

        // more sucessors, we have to add some gotos
      } else {
        // there can be at most two leaving edges
        assert leaving.size() == 2 : leaving.toString();

        CFAEdge leaving1 = leaving.get(0);
        CFAEdge leaving2 = leaving.get(1);

        // outgoing edges have to be handled first, this way
        // we can create the goto easier
        ImmutableSet<CFAEdge> outOfCurrentLoop = loopStack.peek().getOutgoingEdges();

        boolean isOutOfLoopContained = false;
        CFAEdge leavingLoopEdge = null;

        if (outOfCurrentLoop.contains(leaving1)) {
          handleOutOfLoopEdge(
              currentBlock, wholeLoopString, edgesToHandle, loopStack, leaving1, leaving2);
          leavingLoopEdge = leaving1;
          isOutOfLoopContained = true;

        } else if (outOfCurrentLoop.contains(leaving2)) {
          handleOutOfLoopEdge(
              currentBlock, wholeLoopString, edgesToHandle, loopStack, leaving2, leaving1);
          leavingLoopEdge = leaving2;
          isOutOfLoopContained = true;
        }

        if (isOutOfLoopContained) {
          // we are alredy in the outermost loop that should be handled
          // if we have an edge which is leaving this loop we just need
          // to create a goto
          if (loopStack.size() == 1) {
            lastEdge = leavingLoopEdge;
            handledEdges.add(leavingLoopEdge);

            // deeper loopstack, potentially the same code as above
            // we do only need to handle the successor of the outOfLoopEdge, too
          } else {
            outOfLoopEdgesStack.push(leavingLoopEdge);
          }

          // end this loop iteration here
          continue;
        }

        // now comes the case where both edges stay in the loop, this means
        // this is a "simple" if statement
        // we need to find the merging point of both branches, such that we
        // know where the gotos and labels have to go
        if (!handledEdges.contains(leaving1)) {
          wholeLoopString.append(
              processSimpleWithLoop(
                  leaving1,
                  currentBlock,
                  createFreshLabelForIf(
                      leaving2,
                      findEndOfBranches(
                          singletonList(loopToHead.get(loopStack.peek())),
                          currentEdgePredecessor,
                          leaving1.getSuccessor(),
                          leaving2.getSuccessor()))));
          edgesToHandle.push(leaving1);
          ifStack.push(leaving2);
        }
      }
    }

    wholeLoopString
        .append("goto ")
        .append(loopInLabels.get(loop))
        .append(";\n")
        .append(loopOutLabels.get(loop))
        .append(": ;\n");

    //    assert ifOutLabelEnd.isEmpty() && loopStack.isEmpty();
    return Pair.of(wholeLoopString.toString(), lastEdge);
  }

  /**
   * Should only be called by {@link #recreateLoop(CFAEdge, BasicBlock, List)}. It handles out of
   * if-clause edges in a way that the necessary gotos and labels are added
   */
  private void handleIfOutLabels(StringBuilder wholeLoopString, CFANode predecessor) {
    if (!ifOutLabelEnd.isEmpty() && ifOutLabelEnd.peek().containsKey(predecessor)) {
      if (!ifThenHandled.add(predecessor)) {
        wholeLoopString.append(ifOutLabelEnd.pop().get(predecessor)).append(": ;\n");
      }
    }
  }

  /**
   * Should only be called by {@link #recreateLoop(CFAEdge, BasicBlock, List)}. It handles out of
   * loop edges in a way that the necessary gotos and labels are added
   */
  private void handleOutOfLoopEdge(
      BasicBlock currentBlock,
      StringBuilder wholeLoopString,
      Deque<CFAEdge> edgesToHandle,
      Deque<Loop> loopStack,
      CFAEdge leavingEdge,
      CFAEdge stayingEdge) {
    updateLoopStack(wholeLoopString, loopStack, stayingEdge);
    wholeLoopString.append(
        processSimpleWithLoop(leavingEdge, currentBlock, loopOutLabels.get(loopStack.peek())));
    edgesToHandle.push(stayingEdge);
  }

  /**
   * After each processSimpleEdge0 call, all loop recreation related maps, deques and sets have to
   * be cleared. Should only be called from this method also and not from outside.
   */
  private void resetLoopAndIfMaps() {
    loopInLabels = new HashMap<>();
    loopToHead = new HashMap<>();
    loopOutLabels = new HashMap<>();
    ifOutLabels = new HashMap<>();
    ifOutLabelEnd = new ArrayDeque<>();
    ifThenHandled = new HashSet<>();
    ifElseLabels = new HashMap<>();
  }

  /**
   * Updates the loopStack for loopHandling in processSimpleEdge0. It should be only called from
   * there.
   */
  private void updateLoopStack(
      StringBuilder wholeLoopString, Deque<Loop> loopStack, CFAEdge onlyEdge) {
    List<Loop> newLoops = getLoopsOfNode(loopStructure, onlyEdge.getSuccessor());

    // nothing to update in this case
    if (newLoops.isEmpty()) {
      return;
    }

    Loop newInnerLoop = newLoops.get(newLoops.size() - 1);
    if (newInnerLoop.getIncomingEdges().contains(onlyEdge)) {
      String newLabel = createFreshLabelForLoop(onlyEdge, newInnerLoop);
      wholeLoopString.append(newLabel + ": ;\n");
      loopStack.push(newInnerLoop);
    }
  }

  /**
   * Creates a new label for an loop, thus, for the into loop case a label before the loop and for
   * the out of loop case a label after the loop
   *
   * @param pCFAEdge the edge into the loop
   * @param loop the loop for which the labels are needed
   * @return the label to stay in the loop
   */
  private String createFreshLabelForLoop(CFAEdge pCFAEdge, Loop loop) {
    assert !loopInLabels.containsKey(loop);
    String labelStayInLoop = getFreshLabel();
    loopInLabels.put(loop, labelStayInLoop);

    assert !loopToHead.containsKey(loop);
    loopToHead.put(loop, pCFAEdge.getSuccessor());

    assert !loopOutLabels.containsKey(loop);
    String labelGetOutOfLoop = getFreshLabel();
    loopOutLabels.put(loop, labelGetOutOfLoop);

    return labelStayInLoop;
  }

  /**
   * Creates a new label for an "if" statement, thus, for the then case a goto after the else
   * branch, and for the else case a goto after the then branch.
   *
   * @param elseEdge the edge into the else branch
   * @param meetingPoint the point where both branches meet again
   * @return the label to jump to the else branch
   */
  private String createFreshLabelForIf(CFAEdge elseEdge, CFANode meetingPoint) {
    assert !ifElseLabels.containsKey(elseEdge);
    String labelElse = getFreshLabel();
    ifElseLabels.put(elseEdge, labelElse);

    assert !ifOutLabels.containsKey(elseEdge);
    String labelOut = getFreshLabel();
    ifOutLabels.put(elseEdge, labelOut);

    Map<CFANode, String> outMap = new HashMap<>();
    outMap.put(meetingPoint, labelOut);
    ifOutLabelEnd.push(outMap);
    return labelElse;
  }

  /**
   * This method processes a given edge which may be inside of a loop. The suffix is used to jump to
   * a label if we have found an assume edge. If the suffix is empty this is an assume which leads
   * to program termination if not hold, in order to have only the one path of the program we want
   * to analyze afterwards.
   *
   * @param edge the edge to be handled
   * @param currentBlock the currentBlock
   * @param suffix the label to jump to, may be the empty string (in most cases it is) but not null
   * @return the c-code resulting from processing the given edge
   */
  private String processSimpleWithLoop(CFAEdge edge, BasicBlock currentBlock, String suffix) {
    switch (edge.getEdgeType()) {
      case BlankEdge:
      case StatementEdge:
      case ReturnStatementEdge:
      case DeclarationEdge:
        return super.processSimpleEdge(edge, currentBlock) + "\n";

      case AssumeEdge:
        {
          CAssumeEdge lAssumeEdge = (CAssumeEdge) edge;
          if (suffix.isEmpty()) {
            if (currentFunctionName.equals("int main_0()")) {
              return "if(! ("
                  + lAssumeEdge.getCode()
                  + ")) { return 0; }\n"; // we do only want to see the relevant path
            } else {
              return ""; // we cannot just use exit(0) as the invariant generators does
              // cannot cope with non-returning functions
            }
          } else {
            // this is either an out of loop-if or a normal if which has to be redone in a normal
            // way
            // therefore we cannot invert the condition, otherwise the meaning is wrong
            return ("if(" + lAssumeEdge.getCode() + ") { goto " + suffix + "; }\n");
          }
        }

      case FunctionCallEdge:
        {

          // write summary edge to the caller site (with the new unique function name)
          CFunctionEntryNode entryNode =
              ((CFunctionSummaryEdge) ((FunctionCallEdge) edge).getSummaryEdge())
                  .getFunctionEntry();
          String functionName = entryNode.getFunctionName();
          String functionHeader =
              entryNode.getFunctionDefinition().getType().toASTString(functionName);
          // lFunctionHeader is for example "void foo_99(int a)"

          if (!nonUniqueFunctions.containsKey(functionHeader)) {
            nonUniqueFunctions.put(
                functionHeader, Pair.of(entryNode, new FunctionBody(-1, functionHeader)));
          }

          return processFunctionCall(edge, functionName) + "\n";
        }

      default:
        throw new AssertionError("Unexpected edge " + edge + " of type " + edge.getEdgeType());
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Our target state is the end of the program, therefore we return 1 to signalize a wrong
   * program (although for the invariants this will have no meaning at all)
   */
  @Override
  protected String getTargetState() {
    return "return 1;";
  }

  /**
   * This method can be used to find the end of a branching statement, e.g. if then else given the
   * first nodes after the branch it computes the point where both branches are merged together
   * again, or if no merging point was found. It returns the initially given node from the list of
   * intermediateFunctionEnds.
   *
   * @param intermediateFunctionEnds Should contain exactly the last point you know that is
   *     definitely the merging point (e.g. the function end)
   * @param functionStart Should be the node which is definitely above the branching point. E.g. the
   *     function start or the node from which the two branches are leaving
   * @param thenNode the first node in the then branch
   * @param elseNode the first node in the else branch
   * @return The point where both given branches are merged
   */
  private CFANode findEndOfBranches(
      final List<CFANode> intermediateFunctionEnds,
      final CFANode functionStart,
      final CFANode thenNode,
      final CFANode elseNode) {
    return findEndOfBranchesNonRecursive(
        elseNode, thenNode, functionStart, intermediateFunctionEnds.get(0));
  }

  private CFANode findEndOfBranchesNonRecursive(
      CFANode branch1, CFANode branch2, CFANode head, CFANode bottom) {
    Set<CFANode> foundNodes =
        CFATraversal.dfs()
            .backwards()
            .ignoreFunctionCalls()
            .collectNodesReachableFromTo(bottom, head);

    // only if both branches are contained the branching is a real if-clause
    if (foundNodes.contains(branch1) && foundNodes.contains(branch2)) {
      Deque<CFANode> branchStack = new ArrayDeque<>();
      branchStack.push(branch1);
      while (!branchStack.isEmpty()) {
        branch1 = branchStack.pop();

        if (CFATraversal.dfs()
            .backwards()
            .ignoreFunctionCalls()
            .collectNodesReachableFromTo(branch1, head)
            .contains(branch2)) {
          return branch1;
        }

        if (branch1.getNumLeavingEdges() == 2) {
          branchStack.push(branch1.getLeavingEdge(1).getSuccessor());
        }
        CFANode tmpSucc = branch1.getLeavingEdge(0).getSuccessor();
        if (!Objects.equals(tmpSucc, bottom)) {
          branchStack.push(tmpSucc);
        }
      }

      // we did not find the loop although we should have
      throw new AssertionError(
          "We did not find a merging point although there should be one, this is a BUG!");

    } else {
      return bottom;
    }
  }

  /** Returns a new unique label with a number incremented in the end */
  private String getFreshLabel() {
    return "CPAchecker_label_" + labelCounter++;
  }
}
